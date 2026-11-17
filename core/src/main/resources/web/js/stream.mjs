// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-1, package P7 (the spec's §3 "browser stream"; CONTRACT.md §2.4, §2.5): a browser's ops carry its stream
// id, kept in local storage, and keep no queue. The stream id is random 64 bits as text. L1 C1: the PC keeps no
// numbers and checks no order, so a page numbers its ops from 1 in memory — a number only matches an answer to its op.
// Pure: the storage is handed in.

export const STREAM_KEY = 'damage.stream';
export const DEVICE_KEY = 'damage.device';

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
    let id = store.get(STREAM_KEY);
    if (!id || !/^[0-9a-f]{16}$/.test(id)) {
      id = streamId(random(8));
      store.set(STREAM_KEY, id);
    }
    this.id = id;
    this.n = 1;
  }

  /** The next number of this page. */
  next() {
    return this.n++;
  }
}
