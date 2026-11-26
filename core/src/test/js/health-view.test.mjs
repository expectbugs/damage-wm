// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// R-b stage H3: the Health page and home's health line in the real page shell (`app.mjs`) over the DOM stand-in. The
// pins: the page asks for `desc/health` at every welcome; every line is drawn with its reading, since when (the
// service's start named), what is done, and why it is not current; a normal line carries no band word; a line in
// attention or alarm carries its band and the summary names the worst; at level full the diagnosis is the lower half,
// below full it is not asked for and the page says why; out of reach the lines and the last diagnosis stay, "Read
// again" is dim with its reason, and the next socket's description replaces them with no reload; the log box keeps its
// place through a line's change and the reach going (R-b F's pin); home's line is the summary in its band and leads to
// the Health page; the Diagnosis page is gone.

import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { openPage, browserStorage, handTimers, restore } from './pagehost.mjs';
import { healthDesc, H_START } from './health-fixtures.mjs';
import { when } from '../../main/resources/web/js/proto.mjs';

afterEach(restore);

const welcome = (extra = {}) => ({ t: 'welcome', proto: 3, device: 'web-1', level: 'full', conn: 7, pcClock: 1, epoch: 3, seq: 10,
  contracts: { reader: 1 }, bundle: { hash: 'h' }, ...extra });
const DIAG = { build: 'look 0.0', epoch: 3, seq: 40, contracts: { reader: 1 }, connections: ['web-1 · conn 7 · web', 'phone-1 · conn 5 · app lane'],
  log: ['14:01:58 a made-up line', '14:02:00 another made-up line'] };

async function page({ path = '/w/health', level = 'full', health = healthDesc('well') } = {}) {
  const timers = handTimers();
  const p = await openPage({ path, storage: browserStorage(), timers, fetchAnswer: () => { throw new Error('no fetch expected'); } });
  const ws = p.sockets[0];
  ws.open();
  ws.frame(welcome({ level }));
  if (health) ws.frame({ t: 'snap', scope: 'desc/health', desc: health });
  return { p, ws, timers };
}

const all = (root) => root.children.filter((c) => c.tagName).flatMap((c) => [c, ...all(c)]);
const main = (p) => p.doc.byId.main;
const cls = (p, c) => all(main(p)).filter((e) => e.classList.contains(c));
const row = (p, id) => all(main(p)).find((e) => e.getAttribute('data-key') === `health:${id}`);
const subs = (ws, scope) => ws.sent.filter((f) => f.t === 'sub' && f.scope === scope).length;

test('every line is drawn with its reading, since when, what is done and why it is not current; quiet when all is well', async () => {
  const { p, ws } = await page();
  assert.equal(subs(ws, 'desc/health'), 1, 'the page asks for the health record at the welcome');
  assert.equal(cls(p, 'hl').length, 13, 'the thirteen lines');
  assert.deepEqual(cls(p, 'hl').map((e) => e.getAttribute('data-key')).slice(0, 4), ['health:service', 'health:reach', 'health:phone', 'health:hub'],
    'in the record\'s order');
  const service = row(p, 'service').textContent;
  assert.match(service, /^Service/);
  assert.ok(service.includes('up; the record and its operation log are open'));
  assert.ok(service.includes(`since ${when(H_START)} (the service’s start) · shown only`), service);
  const backup = row(p, 'backup').textContent;
  assert.ok(backup.includes(`since ${when(H_START - 10 * 3600e3 - 20 * 60e3)} · daily at 03:30`), 'a source\'s own instant, no start note');
  assert.ok(!backup.includes('the service’s start'), backup);
  // H1's phone lines, drawn as the description carries them
  const reach = row(p, 'reach');
  assert.ok(reach.querySelector('.hl-why'));
  assert.ok(reach.textContent.includes('not reported'));
  assert.ok(reach.textContent.includes('the phone reports its lines when they change and when it connects'));
  assert.ok(reach.textContent.includes('last known: the phone has not reported since the service’s start at 13:50'));
  assert.equal(cls(p, 'hl-band').length, 0, 'no band word on a normal line');
  const sum = cls(p, 'hl-summary')[0];
  assert.equal(sum.textContent, 'All well');
  assert.ok(sum.classList.contains('band-normal'));
});

test('a line in attention or alarm carries its band; the summary names the worst; a stale line says why', async () => {
  const { p } = await page({ health: healthDesc('alarm') });
  assert.deepEqual(cls(p, 'hl-band').map((e) => e.textContent), ['attention', 'alarm'], 'the hub, then the disk');
  assert.ok(row(p, 'hub').classList.contains('band-attention'));
  const disk = row(p, 'disk');
  assert.ok(disk.classList.contains('band-alarm'));
  assert.ok(disk.textContent.includes('alarm1.6 % free of 3.6 TB'), disk.textContent);
  assert.ok(disk.textContent.includes(`since ${when(H_START + 4 * 60e3)} · shown only: nothing is cleaned automatically`));
  const sum = cls(p, 'hl-summary')[0];
  assert.equal(sum.textContent, 'Disk: 1.6 % free of 3.6 TB');
  assert.ok(sum.classList.contains('band-alarm'));
  const cap = row(p, 'cap');
  assert.ok(cap.querySelector('.hl-why'));
  assert.ok(cap.textContent.includes('not current: the read failed: /sys/fs/cgroup/made-up/memory.max: permission denied'));
  assert.ok(row(p, 'error').textContent.includes('a-folder-name-with-no-spaces-in-it-that-goes-on-for-a-long-way/record.json'), 'a long text whole');
});

test('at level full the diagnosis is the lower half; below full it is not asked for and the page says why', async () => {
  const { p, ws } = await page();
  assert.equal(subs(ws, 'diag'), 1, 'at full the diagnosis is asked for');
  assert.ok(main(p).textContent.includes('Reading the PC’s state…'));
  ws.frame({ t: 'snap', scope: 'diag', desc: DIAG });
  assert.equal(cls(p, 'hl').length, 13, 'the lines stay above');
  assert.deepEqual(all(main(p)).filter((e) => e.tagName === 'DT').map((e) => e.textContent), ['PC build', 'Record', 'Contracts', 'This page', 'This device']);
  assert.ok(main(p).textContent.includes('Connections (2)'));
  assert.equal(all(main(p)).find((e) => e.tagName === 'PRE').textContent, DIAG.log.join('\n'));
  const again = all(main(p)).find((e) => e.getAttribute('data-key') === 'diag:again');
  again.fire('click');
  assert.deepEqual(ws.sent.slice(-2).map((f) => `${f.t} ${f.scope}`), ['unsub diag', 'sub diag'], 'Read again asks once more');
  p.go('/');
  assert.equal(ws.sent.at(-1).t, 'unsub', 'leaving the page lets the diagnosis go');
  assert.equal(ws.sent.at(-1).scope, 'diag');

  restore();
  const low = await page({ level: 'view' });
  assert.equal(subs(low.ws, 'diag'), 0, 'below full the diagnosis is not asked for');
  assert.equal(cls(low.p, 'hl').length, 13, 'the lines at level view');
  assert.equal(cls(low.p, 'hl-diag-why')[0].textContent, 'The diagnosis needs level full on this device (this one is view).');
});

test('out of reach the lines and the last diagnosis stay; Read again is dim and says why; the next socket catches up with no reload', async () => {
  const { p, ws, timers } = await page({ health: healthDesc('well') });
  ws.frame({ t: 'snap', scope: 'diag', desc: DIAG });
  const again = () => all(main(p)).find((e) => e.getAttribute('data-key') === 'diag:again');
  const log = () => all(main(p)).find((e) => e.tagName === 'PRE');
  ws.close(); // the PC goes away
  assert.equal(p.doc.byId.reach.hidden, false);
  assert.equal(cls(p, 'hl').length, 13, 'what it last had stays');
  assert.ok(again().classList.contains('dim'), 'Read again is dim out of reach');
  assert.ok(again().getAttribute('data-why'), 'with its reason');
  again().fire('click');
  assert.ok(p.doc.byId.toasts.textContent.includes(again().getAttribute('data-why')), 'a press says why');
  // the next socket: the welcome asks again and its description replaces the lines
  timers.fire(2000);
  const ws2 = p.sockets[1];
  ws2.open();
  ws2.frame(welcome());
  assert.equal(subs(ws2, 'desc/health'), 1, 'asked again on the new socket');
  assert.equal(log().textContent, DIAG.log.join('\n'), 'the press out of reach kept the last diagnosis');
  ws2.frame({ t: 'snap', scope: 'desc/health', desc: healthDesc('alarm') });
  assert.equal(p.doc.byId.reach.hidden, true);
  assert.equal(cls(p, 'hl-summary')[0].textContent, 'Disk: 1.6 % free of 3.6 TB', 'caught up');
  assert.ok(!again().classList.contains('dim'), 'Read again in reach');
  assert.equal(p.win.reloaded, undefined, 'no reload');
});

// R-b F (Part F item 6), the pin: the review's probe (`private/build085/work-RBV/p-logkeep.test.mjs`) taken in. The log
// box is its own scroll box; a new element starts at its top in a real browser, so the element's identity is the
// observable (the stand-in models no layout). Watched failing on the tree before the fix (`e559eca6`).
test('the diagnosis log box keeps its place through a line change and the reach going', async () => {
  const { p, ws } = await page();
  ws.frame({ t: 'snap', scope: 'diag', desc: DIAG });
  const log = () => all(main(p)).find((e) => e.tagName === 'PRE');
  const box = log();
  box.scrollTop = 900; // he scrolled to the newest lines
  ws.frame({ t: 'snap', scope: 'desc/health', desc: healthDesc('attention') });
  assert.ok(log() === box, 'a line\'s change leaves the box as it is'); // identity: a failing equal would print the whole DOM
  ws.close();
  assert.ok(log() === box, 'the reach going leaves the box as it is');
  assert.equal(box.scrollTop, 900);
});

test('home: one line above the cards, the summary in its band, leading to the Health page', async () => {
  const { p, ws } = await page({ path: '/', health: null });
  ws.frame({ t: 'snap', scope: 'desc/main', desc: { cards: [], who: {} } });
  const line = () => cls(p, 'home-health')[0];
  assert.equal(line().textContent, 'Reading the PC’s health…');
  ws.frame({ t: 'snap', scope: 'desc/health', desc: healthDesc('well') });
  assert.equal(line().textContent, 'All well');
  assert.ok(line().classList.contains('band-normal'));
  assert.equal(line().getAttribute('href'), '/w/health');
  const kids = main(p).children[0].children;
  assert.ok(kids.indexOf(line()) < kids.findIndex((e) => e.classList.contains('cards')), 'above the cards');
  ws.frame({ t: 'snap', scope: 'desc/health', desc: healthDesc('attention') });
  assert.equal(line().textContent, 'Home hub: not answering: connection refused');
  assert.ok(line().classList.contains('band-attention'));
  line().fire('click');
  assert.equal(globalThis.location.pathname, '/w/health');
  assert.equal(all(main(p)).find((e) => e.tagName === 'H1').textContent, 'Health');
  assert.equal(cls(p, 'hl').length, 13, 'the record the shell holds, drawn at once');
});

test('the rail names Health at level view; the Diagnosis page is gone', async () => {
  const { p } = await page({ path: '/w/diag', level: 'view' });
  const rail = all(p.doc.byId.rail);
  const item = rail.find((e) => e.getAttribute('data-key') === 'rail:health');
  assert.ok(item, 'Health in the rail');
  assert.ok(!item.classList.contains('dim'), 'at level view');
  assert.equal(rail.filter((e) => e.getAttribute('data-key') === 'rail:diag').length, 0);
  assert.ok(main(p).textContent.includes('This PC serves no window “diag”'), main(p).textContent);
});
