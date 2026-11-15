// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-1, package P7 (the spec's §3 "browser stream"; CONTRACT.md §2.4, §2.5): a browser numbers its ops in its
// own stream, kept in local storage, and keeps no queue. The stream id is random 64 bits as text (a page's numbers hold
// 53 bits); the number continues from the PC's high-water for the stream at each `welcome`. Cleared storage is a new
// stream, said. Pure: the storage is handed in.

export const STREAM_KEY = 'damage.stream';
/** Fix round 1, ruling 2.1: the stream's next number, beside its id. Every tab of the browser shares the stream, so
 *  every tab counts from this one value: each send reads, increments and writes it in one synchronous step. Fix round
 *  2, ruling 2.2: the key carries the stream id (`damage.stream.next.<id>`), so a tab never counts from another
 *  stream's number (site data cleared under an open tab, then a new tab). */
export const NEXT_KEY = 'damage.stream.next';
export const DEVICE_KEY = 'damage.device';
/** S1-2 Q1 (F2b 4): the time of this browser's last approval, written at every redeem. A re-approval of a remembered
 *  device writes the same `DEVICE_KEY`, which fires no `storage` event; this value changes, so a waiting tab hears it. */
export const APPROVED_KEY = 'damage.device.approved';

/** A storage over `ls` (window.localStorage, or a stand-in) that never throws: a failed read or write is kept in
 *  memory and reported once through `failed`. */
export function safeStorage(ls) {
  const mem = new Map();
  const s = {
    failed: null,
    get(k) {
      try {
        const v = ls ? ls.getItem(k) : null;
        return v ?? (mem.has(k) ? mem.get(k) : null);
      } catch (e) {
        s.failed = s.failed || String(e && e.message ? e.message : e);
        return mem.has(k) ? mem.get(k) : null;
      }
    },
    set(k, v) {
      mem.set(k, v);
      try {
        if (!ls) throw new Error('no local storage in this browser');
        ls.setItem(k, v);
      } catch (e) {
        s.failed = s.failed || String(e && e.message ? e.message : e);
      }
    },
    remove(k) {
      mem.delete(k);
      try {
        if (ls) ls.removeItem(k);
      } catch (e) {
        s.failed = s.failed || String(e && e.message ? e.message : e);
      }
    },
  };
  return s;
}

/** 16 hex characters from `bytes` (8 random bytes). */
export function streamId(bytes) {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

export class Stream {
  /** `store` from safeStorage; `random(n)` gives n random bytes. */
  constructor(store, random) {
    this.store = store;
    let id = store.get(STREAM_KEY);
    this.fresh = !id;
    if (!id || !/^[0-9a-f]{16}$/.test(id)) {
      id = streamId(random(8));
      store.set(STREAM_KEY, id);
    }
    this.id = id;
    this.key = `${NEXT_KEY}.${id}`; // a new stream starts at 1
    this.adopted = false;
    /** Ruling 2.2: one past the last number this tab took or renumbered to; the stored number is never read below it
     *  (storage cleared under the tab, a write that did not land). Only a lower bound: the tabs share the stored one. */
    this.floor = 1;
    /** Ruling 2.3: the gap value this connection numbered from last (null = none since its welcome). */
    this.gap = null;
  }

  /** The stream's next number as stored (1 when none is), never below this tab's floor (ruling 2.2). */
  get n() {
    const v = Number(this.store.get(this.key));
    return Math.max(Number.isSafeInteger(v) && v >= 1 ? v : 1, this.floor);
  }

  /**
   * At a `welcome`: the next number is the PC's high-water for this stream plus one, or the stored one when another
   * tab has counted past it. Returns a line to say when the PC knows other streams of this device while this page
   * made a new one (its storage was cleared), else null.
   */
  adopt(highWater) {
    const hw = highWater && typeof highWater[this.id] === 'number' ? highWater[this.id] : 0;
    this.store.set(this.key, String(Math.max(this.n, hw + 1)));
    const said = !this.adopted && this.fresh && highWater && Object.keys(highWater).some((s) => s !== this.id)
      ? "This browser's storage was cleared: its changes start a new stream." : null;
    this.adopted = true;
    this.gap = null;
    return said;
  }

  /** The next number, taken: read, incremented and written in one synchronous step. */
  next() {
    const n = this.n;
    this.floor = n + 1;
    this.store.set(this.key, String(n + 1));
    return n;
  }

  /** The PC expects `n` (a gap): number from there. */
  renumber(n) {
    this.floor = n;
    this.store.set(this.key, String(n));
  }

  /** The PC expects `e` (a gap): the op's new number, counted from there. Fix round 2, ruling 2.3: once per gap value
   *  on a connection, so two ops answered `gap, expected e` in one round trip take e and e + 1, never e twice. */
  gapTo(e) {
    if (this.gap !== e) {
      this.renumber(e);
      this.gap = e;
    }
    return this.next();
  }
}
