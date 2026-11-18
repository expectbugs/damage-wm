// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-1, package P7 (CONTRACT.md §9.2, §4.4, §10.4): the lane's messages as a page sends and reads them, the
// levels, and the words for an answer or an end. The PC is the judge of every op and level; a page's dims are a
// courtesy. Pure.

export const PROTO = 3;
/** The contract versions of this bundle's windows (the PC's `Contracts.MINE`). S1-2 Q3w: the notice record, read as
 *  the `notices` window (spec §2.1), at version 1. S1-2 Q4w: Torrents (spec §5 X1), at version 1. S1-2 Q5w: Files (spec
 *  §5 X1), at version 1. */
export const CONTRACTS = { reader: 1, notices: 1, torrents: 1, files: 1 };

export function hello({ stream, name, replaces }) {
  const h = { t: 'app', proto: PROTO, stream, contracts: { ...CONTRACTS } };
  if (name) h.name = name;
  if (replaces) h.replaces = replaces;
  return h;
}

/** S1-2 Q4w: `need` = a viewer's need of a live source, `{kind: focus | glance | none, paceMs?}` (spec §2.3). L1 C2:
 *  every subscription is answered with a snapshot, so a page names no cursor. */
export function sub(scope, need) {
  const f = { t: 'sub', scope };
  if (need) f.need = need;
  return f;
}

export const unsub = (scope) => ({ t: 'unsub', scope });
export const ping = (k) => ({ t: 'ping', k });

/** A queued op (C§2.4: its stream and number, which only match the answer); the PC puts the connection's device in
 *  place of `device`. L1 C1: `at` = the time of the input on the PC's clock; the PC writes a target unless the item
 *  holds a later input. */
export function queuedOp({ stream, n, window, action, targets, result = null, params, at }) {
  const op = { device: '', stream, n, window, action, targets, result, at };
  if (params) op.params = params;
  return { t: 'op', op };
}

/** A never-queued op of this connection (`conn`, `k`): the device table's actions; S1-2 Q4w: a program op, with its
 *  targets (spec §2.4). */
export function connOp({ conn, k, window, action, params, targets = [], at }) {
  return { t: 'op', op: { device: '', conn, k, window, action, targets, result: null, params, at } };
}

// ---- levels (C§10.4) ----

const RANK = { view: 1, control: 2, full: 3 };

export function atLeast(have, need) {
  return (RANK[have] || 0) >= (RANK[need] || 3);
}

/** Why an action at `need` is dim on a device at `have`, or null. */
export function levelWhy(have, need) {
  return atLeast(have, need) ? null : `needs level ${need} on this device (this one is ${have || 'not approved'})`;
}

// ---- words ----

/** An answer in words for the page (C§4.4); null for one that needs none (seen). */
export function answerWords(a) {
  if (!a) return 'an answer that does not decode';
  switch (a.a) {
    case 'seen':
    case 'sent':
      return null;
    case 'refused':
      return a.reason || `refused (${a.code})`;
    default:
      return `an unknown answer '${a.a}'`;
  }
}

/** A refusal in the page's words where the PC's are a client's: a `retry` ("it is kept and sent again", a phone's
 *  words) is not kept here, so it is said as not applied (L1 P8). */
export function pageWords(a) {
  if (!a || a.a !== 'refused' || a.code !== 'retry') return a;
  return { ...a, reason: `not applied: ${(a.reason || 'the PC could not take it').split(';')[0]}; act again` };
}

/** An `end` in words (C§9.2, C§10.8). */
export function endWords(reason, detail) {
  switch (reason) {
    case 'revoked':
      return 'This browser was removed from the device table. It asks for approval again.';
    case 'replaced':
      return 'This device was approved again elsewhere; this page asks again.';
    case 'denied':
      return 'The approval was refused on the PC.';
    case 'slow':
      return 'The PC ended the connection because this page fell behind; it resumes now.';
    case 'proto':
      return `The PC and this page speak different versions of the lane${detail ? ` (${detail})` : ''}; the page reloads.`;
    case 'not open':
      return `The PC's record is not open${detail ? ` (${detail})` : ''}; the page tries again.`;
    case 'shutting down':
      return 'The PC is restarting; the page reconnects.';
    default:
      return `The PC ended the connection (${reason}${detail ? `: ${detail}` : ''}).`;
  }
}

/** Whether a page reconnects on its own after an `end` with this reason (`proto`: it reloads instead, L1). */
export function endRetries(reason) {
  return reason !== 'proto' && reason !== 'denied';
}

/** H:MM in the page's local time (the glasses' `hhmm`). */
export function hhmm(ms) {
  const d = new Date(ms);
  return `${d.getHours()}:${String(d.getMinutes()).padStart(2, '0')}`;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
/** H:MM, with the day when it is not today (a TV left over a weekend reads "Nov 15 14:02", never as today). */
export function when(ms, now = Date.now()) {
  const d = new Date(ms);
  return d.toDateString() === new Date(now).toDateString() ? hhmm(ms) : `${MONTHS[d.getMonth()]} ${d.getDate()} ${hhmm(ms)}`;
}
