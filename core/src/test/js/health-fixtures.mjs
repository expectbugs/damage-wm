// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// R-b stage H3: `desc/health` in the shape `HealthCore.describe` makes it (H1, `core/…/app/Health.kt`) — its thirteen
// lines in their order, the PC's in the readers' words, the phone's four as H1 leaves them until H2 ("not reported",
// last known) — for the Health page's tests and the stand-in's scenes. Every reading is made up.

/** The service's start in these fixtures (the PC's clock). */
export const H_START = Date.UTC(2026, 10, 15, 13, 50);

const pc = (id, label, text, doing, more = {}) => ({ id, label, text, band: 'normal', since: H_START, asOf: H_START, source: 'pc', state: 'live', doing, ...more });
const phone = (id, label) => ({ id, label, text: 'not reported', band: 'normal', since: H_START, asOf: H_START, source: 'phone', state: 'lastKnown',
  reason: 'the phone has not reported since the service’s start at 13:50', doing: 'the phone reports its lines when they change and when it connects' });
const ONLY = 'shown only';

function wellLines() {
  return [
    pc('service', 'Service', 'up; the record and its operation log are open', ONLY),
    phone('reach', 'PC reach'),
    pc('phone', 'Phone', 'attached', ONLY),
    pc('hub', 'Home hub', 'answering', 'asking every 10 s'),
    pc('backup', 'Nightly copy', 'the newest copy at 03:30 (made-up-copy-1115)', 'daily at 03:30', { since: H_START - 10 * 3600e3 - 20 * 60e3 }),
    pc('cap', 'Memory cap', 'claude-work: 16 GiB, no swap', 'shown only: the group is made at the PC’s start; read every 10 minutes'),
    phone('glasses.link', 'Glasses link'),
    phone('glasses.battery', 'Glasses battery'),
    phone('glasses.stage', 'Glasses stage'),
    pc('builds', 'Builds', 'PC desktop 0.0 (made up)', ONLY),
    pc('disk', 'Disk', '71 % free of 3.6 TB', 'shown only: nothing is cleaned automatically; read every minute'),
    pc('error', 'Last error', 'none since the service’s start', 'shown only: each error’s own path says what is done about it'),
    pc('sources', 'Sources', 'all 6 live', 'each window asks its own source again and says so in it'),
  ];
}

/** `desc/health`: `well` (every line normal), `attention` (the hub not answering), `alarm` (the disk nearly full, the
 *  hub not answering, the cap's read failing, and a long error that must wrap). */
export function healthDesc(kind = 'well') {
  const lines = wellLines();
  const set = (id, change) => { const i = lines.findIndex((l) => l.id === id); lines[i] = { ...lines[i], ...change }; };
  if (kind === 'attention' || kind === 'alarm') {
    set('hub', { text: 'not answering: connection refused', band: 'attention', since: H_START + 12 * 60e3, asOf: H_START + 12 * 60e3,
      doing: 'asking every 10 s; the alert after 5 min' });
  }
  if (kind === 'alarm') {
    set('disk', { text: '1.6 % free of 3.6 TB', band: 'alarm', since: H_START + 4 * 60e3, asOf: H_START + 9 * 60e3, tag: 'disk 98%' });
    set('cap', { state: 'stale', reason: 'the read failed: /sys/fs/cgroup/made-up/memory.max: permission denied' });
    set('error', { text: '3 since the service’s start; the last at 14:01: could not write /home/made-up/a-folder-name-with-no-spaces-in-it-that-goes-on-for-a-long-way/record.json',
      asOf: H_START + 11 * 60e3 });
  }
  const order = { normal: 0, attention: 1, alarm: 2 };
  const worst = lines.reduce((w, l) => (order[l.band] > order[w.band] ? l : w), lines[0]);
  return { id: 'health', name: 'Health', min: 'view', availability: 'live', started: H_START,
    worst: worst.band === 'normal' ? { band: 'normal' } : { id: worst.id, band: worst.band },
    summary: { line: worst.band === 'normal' ? 'All well' : `${worst.label}: ${worst.text}` }, lines };
}
