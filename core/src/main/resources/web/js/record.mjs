// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-1, package P7 (CONTRACT.md §9.3): a page's copy of the record's entries it subscribed to — a `snap` at
// (epoch, seq), then `chg`s in order. A change at or below the snapshot's seq is ignored; a change of another epoch
// asks for a fresh snapshot, never a silent gap. A page keeps no queue (§2.5): this is a read copy. Pure.

/** The record key of an item `name` of `window` (`Record.key` in Kotlin). */
export function key(window, name) {
  return `rec.${window}.${name}`;
}

export class ClientRecord {
  constructor() {
    this.items = new Map();
    this.epoch = null;
    this.seq = null;
  }

  /** A snapshot of the windows in `prefixes`: their entries replaced whole. Returns the keys that changed. */
  snap(f, prefixes) {
    const changed = new Set();
    for (const k of [...this.items.keys()]) {
      if (prefixes.some((p) => k.startsWith(p)) && !(f.items && k in f.items)) {
        this.items.delete(k);
        changed.add(k);
      }
    }
    for (const [k, v] of Object.entries(f.items || {})) {
      if (!same(this.items.get(k), v)) changed.add(k);
      this.items.set(k, v);
    }
    this.epoch = f.epoch;
    this.seq = f.seq;
    return changed;
  }

  /**
   * One change. Returns the keys that changed, an empty set for a change already held, or null when it belongs to
   * another epoch (the caller subscribes again without a cursor and gets a snapshot).
   */
  chg(f) {
    if (this.epoch === null || f.epoch !== this.epoch) return null;
    if (f.seq <= this.seq) return new Set();
    const changed = new Set();
    for (const p of f.patches || []) {
      if (!p.set) continue;
      if (!same(this.items.get(p.addr), p.set)) changed.add(p.addr);
      this.items.set(p.addr, p.set);
    }
    this.seq = f.seq;
    return changed;
  }

  item(window, name) {
    return this.items.get(key(window, name));
  }

  /** The live value of an item, or undefined (absent or removed). */
  value(window, name) {
    const it = this.item(window, name);
    return it && !it.gone ? it.v : undefined;
  }
}

function same(a, b) {
  return JSON.stringify(a) === JSON.stringify(b);
}
