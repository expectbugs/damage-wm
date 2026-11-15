// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-2, package Q3w (the spec's §2.1 "Web"; CONTRACT.md §7; decision A.5): which of the PC's notices a page pops
// as toasts, holds or takes down, and the ops its Dismiss and its tap-through send. The record is the `notices` window
// of the PC record: `rec.notices.<source>:<thread>` = {occ, raisedAt, app, body, urgency, state, target, …}. Each tab
// pops only records raised past its notice cursor (epoch, occ); the cursor is kept per stream in local storage, so a
// reload pops nothing it already handled, and first contact or a new epoch adopts the highest occ and pops nothing
// (C§7.3). Quiet hours hold what the record says they hold (`heldUntil`, set on the PC); the page decides none of it.
// Pure: the storage and the clock are handed in.

export const WINDOW = 'notices';
export const PREFIX = `rec.${WINDOW}.`;
/** The stored cursor of a stream: `damage.notices.cursor.<stream>` = {"epoch": e, "occ": n}. */
export const cursorKey = (stream) => `damage.notices.cursor.${stream}`;
const LIVE = new Set(['RAISED', 'READ']);

/** An occurrence as a whole number (the PC may write it as a number or as its decimal text), else null. */
export function occOf(v) {
  const o = v && v.occ;
  if (typeof o === 'number' && Number.isSafeInteger(o) && o >= 0) return o;
  if (typeof o === 'string' && /^\d{1,15}$/.test(o)) return Number(o);
  return null;
}

/** The notices of a record copy's items: [{id, v, occ}] for every live-or-not value under PREFIX; `bad` lists the ids
 *  whose value carries no occurrence (said by the page, never dropped unsaid). */
export function notices(items) {
  const all = [];
  const bad = [];
  for (const [k, it] of items) {
    if (!k.startsWith(PREFIX) || !it || it.gone) continue;
    const id = k.slice(PREFIX.length);
    const occ = occOf(it.v);
    if (occ === null) bad.push(id);
    else all.push({ id, v: it.v, occ });
  }
  return { all, bad };
}

/** The stored cursor of `stream`, or null (none, or not readable). */
export function readCursor(store, stream) {
  try {
    const c = JSON.parse(store.get(cursorKey(stream)) || 'null');
    return c && Number.isSafeInteger(c.epoch) && Number.isSafeInteger(c.occ) ? c : null;
  } catch (e) {
    return null;
  }
}

/** One op on a notice (C§4.2: a reading effect, a register at level view): the whole value with its state moved, and
 *  the occurrence it was seen at as text (`Expect.Occurrence` reads text) — a later occurrence makes it lose, unsaid. */
export function draft(n, action, state) {
  return { window: WINDOW, action, targets: [n.id], result: { ...n.v, state }, expect: { occ: String(n.occ) } };
}
export const dismissDraft = (n) => draft(n, 'dismiss', 'DISMISSED');
export const readDraft = (n) => draft(n, 'read', 'READ');

/** One tab's notices: what it shows (`up`: id → occ), what it holds for quiet hours (`held`), and its cursor. */
export class NoticeDesk {
  constructor(store, stream) {
    this.store = store;
    this.stream = stream;
    this.cursor = null; // {epoch, occ}: every record at or below it was popped, held or adopted by this tab
    this.up = new Map();
    this.held = new Map();
  }

  /**
   * After the notices scope changed (a snap or a chg at `epoch`), or at a hold's end: `pop` the records to show now
   * (oldest first), `withdraw` the ids whose toast must leave (dismissed, acknowledged or cleared elsewhere, or a newer
   * occurrence), `bad` the ids whose value has no occurrence, `wake` the PC instant of the next hold's end (or null).
   * `pcNow` is the PC's clock.
   */
  review(epoch, items, pcNow) {
    const { all, bad } = notices(items);
    const byId = new Map(all.map((n) => [n.id, n]));
    const top = all.reduce((m, n) => Math.max(m, n.occ), 0);
    const withdraw = [];
    for (const [id, occ] of this.up) {
      const n = byId.get(id);
      if (!n || n.occ !== occ || !LIVE.has(n.v.state)) {
        this.up.delete(id);
        withdraw.push(id);
      }
    }
    for (const [id, occ] of this.held) {
      const n = byId.get(id);
      if (!n || n.occ !== occ || n.v.state !== 'RAISED') this.held.delete(id);
    }
    const pop = [];
    if (!this.cursor || this.cursor.epoch !== epoch) {
      const stored = readCursor(this.store, this.stream);
      if (!this.cursor && stored && stored.epoch === epoch) this.cursor = stored;
      else {
        // first contact, a cleared store or a new epoch: adopt, pop nothing old (C§7.3)
        this.cursor = { epoch, occ: top };
        this.held.clear();
      }
    }
    for (const n of all) {
      if (n.occ <= this.cursor.occ || n.v.state !== 'RAISED') continue;
      if (typeof n.v.heldUntil === 'number' && n.v.heldUntil > pcNow) this.held.set(n.id, n.occ);
      else pop.push(n);
    }
    // a hold ends: everything it held is released together
    for (const [id] of this.held) {
      const n = byId.get(id);
      if (!(n.v.heldUntil > pcNow)) {
        this.held.delete(id);
        pop.push(n);
      }
    }
    this.cursor.occ = Math.max(this.cursor.occ, top);
    pop.sort((a, b) => a.occ - b.occ);
    for (const n of pop) this.up.set(n.id, n.occ);
    this.save();
    const ends = [...this.held.keys()].map((id) => byId.get(id).v.heldUntil);
    return { pop, withdraw, bad, wake: ends.length ? Math.min(...ends) : null };
  }

  /** The toast of `id` left for a reason of the page's own (it timed out, the cap, Dismiss, the tap-through). */
  down(id) {
    this.up.delete(id);
  }

  /** The stored cursor never passes a record this tab still holds (a page closed during quiet hours pops it at its
   *  next visit), and is never moved back by a tab behind another (the tabs share it). */
  save() {
    let occ = this.cursor.occ;
    for (const o of this.held.values()) occ = Math.min(occ, o - 1);
    const stored = readCursor(this.store, this.stream);
    if (stored && stored.epoch === this.cursor.epoch && stored.occ >= occ) return;
    this.store.set(cursorKey(this.stream), JSON.stringify({ epoch: this.cursor.epoch, occ }));
  }
}
