// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-1, package P7 (CONTRACT.md §9.2, §4.4, §10.4): the lane's messages as a page sends and reads them, the
// levels, and the words for an answer or an end. The PC is the judge of every op and level; a page's dims are a
// courtesy. Pure.

export const PROTO = 2;
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

/** S1-2 Q4w: `need` = a viewer's need of a live source, `{kind: focus | glance | none, paceMs?}` (spec §2.3). */
export function sub(scope, cursor, need) {
  const f = { t: 'sub', scope };
  if (cursor) {
    f.epoch = cursor.epoch;
    f.seq = cursor.seq;
  }
  if (need) f.need = need;
  return f;
}

export const unsub = (scope) => ({ t: 'unsub', scope });
export const ping = (k) => ({ t: 'ping', k });

/** A queued op (C§2.4: its stream and number); the PC puts the connection's device in place of `device`. */
export function queuedOp({ stream, n, window, action, targets, result = null, expect = { any: true }, params }) {
  const op = { device: '', stream, n, window, action, targets, result, expect };
  if (params) op.params = params;
  return { t: 'op', op };
}

/** A never-queued op of this connection (`conn`, `k`): the device table's actions; S1-2 Q4w: a program op, with its
 *  targets (spec §2.4). */
export function connOp({ conn, k, window, action, params, targets = [] }) {
  return { t: 'op', op: { device: '', conn, k, window, action, targets, result: null, expect: { any: true }, params } };
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
    case 'lost':
      return 'not applied';
    default:
      return `an unknown answer '${a.a}'`;
  }
}

/** Fix round 2, ruling 2.3: a `retry` this page already sent again once, in the page's own words. The PC's words
 *  ("it is kept and sent again") are a phone's, which keeps its queue; a page keeps none. */
export function givenUp(a) {
  const why = (a.reason || `refused (${a.code})`).split(';')[0];
  return { ...a, reason: `${why}; this page keeps no queue: act again later` };
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
      return `The PC and this page speak different versions of the lane${detail ? ` (${detail})` : ''}. Reload the page.`;
    case 'not open':
      return `The PC's record is not open${detail ? ` (${detail})` : ''}; the page tries again.`;
    case 'shutting down':
      return 'The PC is restarting; the page reconnects.';
    default:
      return `The PC ended the connection (${reason}${detail ? `: ${detail}` : ''}).`;
  }
}

/** Whether a page reconnects on its own after an `end` with this reason. */
export function endRetries(reason) {
  return reason !== 'proto' && reason !== 'denied';
}

/** H:MM in the page's local time (the glasses' `hhmm`). */
export function hhmm(ms) {
  const d = new Date(ms);
  return `${d.getHours()}:${String(d.getMinutes()).padStart(2, '0')}`;
}
