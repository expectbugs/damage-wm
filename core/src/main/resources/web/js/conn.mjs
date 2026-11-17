// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-1, package P7 (CONTRACT.md §9.3 "reach and pacing"): a page's one connection to the PC. It pings on the
// wire's pacing; a ping still unanswered when the next is due means the PC is out of reach — that decision rests on
// the pings alone, never on a failed write. A closed socket is reconnected on 2 s pacing, for ever (pacing, not a
// bound on any work); L1: so is a socket with two pings unanswered (a half-open socket would otherwise wait for the
// OS to give up on it). The socket, the clock and the timers are handed in, so the decision is tested without a browser.

import { ping } from './proto.mjs';

export const PING_EVERY_MS = 15000;
export const RETRY_MS = 2000;

export class Conn {
  constructor({ url, hello, onFrame, onReach, onOpen, WS, now = () => Date.now(), timers = globalThis,
    pingEveryMs = PING_EVERY_MS, retryMs = RETRY_MS }) {
    Object.assign(this, { url, hello, onFrame, onReach, onOpen, WS, now, timers, pingEveryMs, retryMs });
    this.ws = null;
    this.k = 0;
    this.waiting = null; // the unanswered ping: {k, at}
    this.missed = 0; // pings unanswered in a row on this socket
    this.reach = { ok: false, since: now() };
    this.stopped = false;
    this.pinger = null;
    this.retry = null;
  }

  start() {
    this.stopped = false;
    this.connect();
  }

  /** Sends one frame; false when there is no open socket (the caller says it: a page keeps no queue). */
  send(f) {
    if (!this.ws || this.ws.readyState !== 1) return false;
    this.ws.send(JSON.stringify(f));
    return true;
  }

  /** Ends the connection for good (the page is leaving). */
  stop() {
    this.stopped = true;
    this.timers.clearInterval(this.pinger);
    this.timers.clearTimeout(this.retry);
    if (this.ws) this.ws.close();
  }

  /** The PC answered (a pong, or a welcome on a new socket): in reach. */
  answered() {
    this.waiting = null;
    this.missed = 0;
    if (!this.reach.ok) {
      this.reach = { ok: true, since: this.now() };
      if (this.onReach) this.onReach(this.reach);
    }
  }

  away(since) {
    if (this.reach.ok) {
      this.reach = { ok: false, since };
      if (this.onReach) this.onReach(this.reach);
    }
  }

  connect() {
    const ws = new this.WS(this.url);
    this.ws = ws;
    ws.onopen = () => {
      ws.send(JSON.stringify(this.hello()));
      this.waiting = null;
      this.missed = 0;
      this.timers.clearInterval(this.pinger);
      this.pinger = this.timers.setInterval(() => this.tick(), this.pingEveryMs);
      if (this.onOpen) this.onOpen();
    };
    ws.onmessage = (e) => {
      if (this.ws !== ws) return; // a socket let go
      let f;
      try {
        f = JSON.parse(e.data);
      } catch (err) {
        f = { t: 'undecodable', detail: String(err) };
      }
      if (f.t === 'pong') {
        // any pong: the PC's core answered a ping of this socket
        this.answered();
        return;
      }
      // a new socket's first answer (the lane's welcome, or a waiting page's code): the PC answers
      if (f.t === 'welcome' || f.t === 'pending') this.answered();
      this.onFrame(f);
    };
    ws.onclose = () => this.closed(ws);
    ws.onerror = () => {
      // a close follows every error; the close decides
    };
  }

  /** `ws` ended (or was let go): out of reach, said to the page, and a new socket on the pacing. */
  closed(ws) {
    if (this.ws !== ws) return;
    this.ws = null;
    this.timers.clearInterval(this.pinger);
    this.away(this.now());
    this.onFrame({ t: 'closed' });
    if (!this.stopped) this.retry = this.timers.setTimeout(() => this.connect(), this.retryMs);
  }

  /** Lets the socket go now and makes a new one on the pacing: two pings unanswered, or the page's own ask (an upload
   *  cancelled before its body began: the PC lets go of what that connection held, L1 P41). */
  drop() {
    const ws = this.ws;
    if (!ws) return;
    this.closed(ws);
    ws.close();
  }

  /** The wire's pacing: the last ping unanswered now = out of reach since it went out; the second in a row lets the
   *  socket go. */
  tick() {
    if (this.waiting) {
      this.away(this.waiting.at);
      if (++this.missed >= 2) return this.drop();
    }
    const k = ++this.k;
    // away() acts on the change only, so the first unanswered ping of a streak names the time
    if (this.send(ping(k))) this.waiting = { k, at: this.now() };
  }
}
