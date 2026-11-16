// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-2, package Q5w: the Files page in the real page shell (`app.mjs`) over the DOM stand-in. The pins: below
// level full nothing of Files is asked or shown, and a level lowered by a re-approval lets it all go; an address
// naming an entry reads pages until it is held; Move to Trash asks once naming the thing and its job's end is said;
// Delete forever asks twice; a confirm open over a connection's end sends nothing; dropped files go one at a time,
// the upload op then its body; a cut-off upload is said and stops the rest; a taken name asks first (Skip, Replace by
// the trash); out of reach nothing is sent and the page says why; a pasted cut is cleared by the PC core, never the page; Sort and
// Hidden are shared rows; several selected; the text preview; the job line and its dims.

import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { openPage, browserStorage, handTimers, restore, settle } from './pagehost.mjs';
import { format } from '../../main/resources/web/js/address.mjs';
import { filesDesc, filesRecord, folderRows, folderPage, runningCopy, TRASH, DL, F_NOW } from './files-fixtures.mjs';

afterEach(restore);

const welcome = (stream, extra = {}) => ({ t: 'welcome', proto: 2, device: 'web-1', level: 'full', conn: 7, pcClock: F_NOW, epoch: 3, seq: 10,
  highWater: {}, contracts: { reader: 1, notices: 1, torrents: 1, files: 1 }, bundle: { version: 't', hash: 'h', protoRange: [2, 2] }, ...extra });
const at = (target) => format({ window: 'files', target });

class FakeXHR {
  constructor() {
    this.upload = {};
    FakeXHR.all.push(this);
  }
  open(method, url) { Object.assign(this, { method, url }); }
  send(body) { this.body = body; }
  abort() { if (this.onabort) this.onabort(); }
}

async function page({ path = at(`path:${DL}/`), level = 'full', desc = filesDesc(), record = filesRecord(), rows = folderRows(40), narrow = false } = {}) {
  const timers = handTimers();
  const p = await openPage({ path, storage: browserStorage(), timers, fetchAnswer: () => { throw new Error('no fetch expected'); } });
  if (narrow) p.win.matchMedia = () => ({ matches: true });
  FakeXHR.all = [];
  globalThis.XMLHttpRequest = FakeXHR;
  const ws = p.sockets[0];
  ws.open();
  ws.frame(welcome(ws.sent[0].stream, { level }));
  if (desc) ws.frame({ t: 'snap', scope: 'desc/files', desc });
  if (record) ws.frame(record);
  if (rows && subs(ws).some((f) => f.scope === `files/folder:${DL}/page:0`)) ws.frame({ t: 'snap', scope: `files/folder:${DL}/page:0`, desc: folderPage(rows, 0) });
  return { p, ws, timers, stream: ws.sent[0].stream, rows };
}

const all = (root) => root.children.filter((c) => c.tagName).flatMap((c) => [c, ...all(c)]);
const main = (p) => p.doc.byId.main;
const find = (p, tag, text) => all(main(p)).find((e) => e.tagName === tag && (text === undefined || e.textContent === text));
const findAll = (p, tag) => all(main(p)).filter((e) => e.tagName === tag);
const byKey = (p, k) => all(main(p)).find((e) => e.getAttribute('data-key') === k);
const subs = (ws) => ws.sent.filter((f) => f.t === 'sub');
const progOps = (ws) => ws.sent.filter((f) => f.t === 'op' && f.op.conn !== undefined).map((f) => f.op);
const queuedOps = (ws) => ws.sent.filter((f) => f.t === 'op' && f.op.stream !== undefined).map((f) => f.op);
const dialog = (p) => p.doc.byId.confirm;
const dlgButton = (p, text) => all(dialog(p)).find((e) => e.tagName === 'BUTTON' && e.textContent === text);
const section = (p) => main(p).children[0];
const drop = (p, files) => section(p).fire('drop', { dataTransfer: { types: ['Files'], files } });
const file = (name, size = 100) => ({ name, size });
const sent = (ws, k, answer) => ws.frame({ t: 'ans', conn: 7, k, answer });

test('below level full the page says so and asks the PC for nothing of Files; a re-approval lowering it lets go', async () => {
  const { p, ws } = await page({ level: 'control', desc: null, record: null });
  assert.equal(subs(ws).some((f) => f.scope === 'desc/files' || f.scope === 'files' || f.scope.startsWith('files/')), false, 'nothing of the tree asked');
  assert.match(main(p).textContent, /Files needs level full on this device \(this one is control\): nothing of the PC's files is shown here/);
  const rail = all(p.doc.byId.rail).find((e) => e.getAttribute('data-key') === 'rail:files');
  assert.ok(rail.classList.contains('dim'), 'dim in the rail');
  // approved again at full on the same page: Files starts
  ws.frame(welcome(ws.sent[0].stream, { level: 'full' }));
  assert.ok(subs(ws).some((f) => f.scope === 'desc/files'));
  ws.frame({ t: 'snap', scope: 'desc/files', desc: filesDesc() });
  ws.frame({ t: 'snap', scope: `files/folder:${DL}/page:0`, desc: folderPage(folderRows(5), 0) });
  assert.ok(findAll(p, 'LI').length > 0, 'the folder shown at full');
  // and lowered again: every Files scope let go, nothing of the tree left on the page
  ws.frame(welcome(ws.sent[0].stream, { level: 'view' }));
  const gone = ws.sent.filter((f) => f.t === 'unsub').map((f) => f.scope);
  assert.ok(gone.includes('desc/files') && gone.includes('files') && gone.includes(`files/folder:${DL}/page:0`), gone.join(', '));
  assert.equal(findAll(p, 'LI').length, 0);
  assert.match(main(p).textContent, /this one is view/);
});

test('an address naming an entry reads the folder\'s pages until it is held, then shows its pane', async () => {
  const rows = folderRows(1200);
  const name = rows[700].name;
  const { p, ws } = await page({ path: at(`path:${DL}/${name}`), rows: null });
  const pages = () => subs(ws).filter((f) => /\/page:\d+$/.test(f.scope)).map((f) => f.scope);
  assert.deepEqual(pages(), [`files/folder:${DL}/page:0`]);
  assert.match(main(p).textContent, new RegExp(`Looking for '${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}'`));
  ws.frame({ t: 'snap', scope: `files/folder:${DL}/page:0`, desc: folderPage(rows, 0) });
  assert.deepEqual(pages().at(-1), `files/folder:${DL}/page:1`, 'asked without a scroll: the named entry is not held yet');
  ws.frame({ t: 'snap', scope: `files/folder:${DL}/page:1`, desc: folderPage(rows, 1) });
  assert.equal(pages().length, 2, 'held: nothing more asked');
  assert.ok(find(p, 'H2', name), 'its pane');
});

test('Move to Trash asks once naming the entry, sends one program op, and says the job\'s end from the slot', async () => {
  const { p, ws, rows } = await page();
  const r = rows.find((x) => !x.dir && x.name.endsWith('.txt'));
  p.go(at(`path:${DL}/${r.name}`));
  find(p, 'BUTTON', 'Move to Trash').fire('click');
  assert.match(dialog(p).textContent, new RegExp(`Delete '${r.name}'\\?`));
  dialog(p).querySelector('button.act').fire('click');
  await settle();
  const [op] = progOps(ws);
  assert.deepEqual([op.window, op.action, op.targets, op.conn], ['files', 'trash', [`${DL}/${r.name}`], 7]);
  assert.match(main(p).textContent, /Move to Trash · 1 item · sent, no answer from the PC yet/);
  sent(ws, op.k, { a: 'sent', result: { job: 'j9' } });
  assert.equal(p.said().some((t) => /done/.test(t)), false, 'Sent is not the end of the job');
  ws.frame({ t: 'snap', scope: 'desc/files', desc: filesDesc({ jobs: [{ id: 'j9', label: 'trashing', state: 'done', startedAt: F_NOW, updatedAt: F_NOW, answer: 'moved to trash' }] }) });
  assert.match(p.said().at(-1), /Move to Trash: done — moved to trash/);
});

test('Delete forever asks twice; the first act sends nothing', async () => {
  const { p, ws } = await page({ path: at('trash'), rows: null });
  ws.frame({ t: 'snap', scope: 'files/trash', desc: { rows: TRASH } });
  findAll(p, 'BUTTON').filter((b) => b.textContent === 'Delete forever')[0].fire('click');
  assert.match(dialog(p).textContent, /Delete '(.*)' forever\?/);
  dialog(p).querySelector('button.act').fire('click');
  await settle();
  assert.deepEqual(progOps(ws), [], 'one act is not enough');
  assert.match(dialog(p).textContent, /second ask; it cannot be undone/);
  dialog(p).querySelector('button.act').fire('click');
  await settle();
  assert.deepEqual(progOps(ws).map((o) => [o.action, o.targets]), [['purge', ['t1']]]);
});

test('a confirm open while the connection ended sends nothing when it is taken (C§4.3)', async () => {
  const { p, ws, timers, stream, rows } = await page();
  const r = rows.find((x) => !x.dir);
  p.go(at(`path:${DL}/${r.name}`));
  find(p, 'BUTTON', 'Move to Trash').fire('click');
  p.sockets[0].close();
  timers.timeouts.filter((x) => x.live && x.ms === 2000).at(-1).fn();
  const ws2 = p.sockets.at(-1);
  ws2.open();
  ws2.frame(welcome(stream, { conn: 8 }));
  dialog(p).querySelector('button.act').fire('click');
  await settle();
  assert.deepEqual(progOps(ws).concat(progOps(ws2)), []);
  assert.match(p.said().join(' | '), /nothing sent — the connection to the PC ended while the confirm was open/);
});

test('dropped files go one at a time: the upload op, then its body under the id the PC gave, then the next', async () => {
  const { p, ws } = await page();
  drop(p, [file('new a.txt', 200), file('new b.txt', 300)]);
  assert.deepEqual(progOps(ws).map((o) => [o.action, o.targets, o.params]), [['upload', [DL], { file: { name: 'new a.txt', size: 200 } }]]);
  sent(ws, progOps(ws)[0].k, { a: 'sent', result: { upload: 'u-1' } });
  const [x] = FakeXHR.all;
  assert.deepEqual([x.method, x.url, x.body.name], ['PUT', '/blob/upload/u-1', 'new a.txt']);
  x.upload.onprogress({ loaded: 100, total: 200, lengthComputable: true });
  assert.match(main(p).textContent, /50% · 100 B of 200 B/);
  // a second drop while one is being sent waits its turn (the PC holds one slot)
  drop(p, [file('later.txt', 50)]);
  assert.equal(progOps(ws).length, 1, 'the next waits for this one');
  Object.assign(x, { status: 201, responseText: '' });
  x.onload();
  assert.match(p.said().at(-1), /Uploaded 'new a\.txt' to Downloads\./);
  assert.deepEqual(progOps(ws).at(-1).params, { file: { name: 'new b.txt', size: 300 } });
});

test('a cut-off upload is said, and the files after it are not sent', async () => {
  const { p, ws } = await page();
  drop(p, [file('one.bin', 1000), file('two.bin', 1000)]);
  sent(ws, progOps(ws)[0].k, { a: 'sent', result: { upload: 'u-1' } });
  const [x] = FakeXHR.all;
  x.upload.onprogress({ loaded: 400, total: 1000, lengthComputable: true });
  x.onerror();
  const said = p.said().join(' | ');
  assert.match(said, /'one\.bin': the upload was cut off at 400 B of 1000 B \(the connection to the PC dropped\); nothing is kept under that name/);
  assert.match(said, /1 more file was not uploaded/);
  assert.equal(progOps(ws).length, 1, 'two.bin not sent');
});

test('a dropped file whose name is in the folder asks first: Skip sends nothing; Replace moves the old one to the trash, then uploads', async () => {
  const { p, ws, rows } = await page();
  const name = rows.find((x) => !x.dir).name;
  drop(p, [file(name)]);
  assert.match(dialog(p).textContent, new RegExp(`'${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}' is already in Downloads`));
  dlgButton(p, 'Skip').fire('click');
  await settle();
  assert.deepEqual(progOps(ws), []);
  drop(p, [file(name)]);
  all(dialog(p)).find((e) => e.getAttribute('data-choice') === 'replace').fire('click');
  await settle();
  const [t] = progOps(ws);
  assert.deepEqual([t.action, t.targets], ['trash', [`${DL}/${name}`]]);
  assert.equal(progOps(ws).length, 1, 'nothing uploaded before the old one is in the trash');
  sent(ws, t.k, { a: 'sent' });
  assert.deepEqual(progOps(ws).at(-1).params, { file: { name, size: 100 } });
});

test('out of reach, dropped files are not sent and the page says why', async () => {
  const { p, ws, timers } = await page();
  timers.tick();
  timers.tick();
  drop(p, [file('a.txt'), file('b.txt')]);
  assert.deepEqual(progOps(ws), []);
  assert.match(p.said().join(' | '), /2 files were not uploaded: PC not answering since .*A page keeps no queue/);
});

test('a pasted cut sends the paste alone: the PC core clears the clipboard (S1-2 R0 §3), and the page shows the record', async () => {
  const clip = { verb: 'cut', path: '/home/ada/notes.txt' };
  const { p, ws } = await page({ record: filesRecord({ 'rec.files.clip': { v: clip, rev: 3, by: 'phone-1', at: 1 } }) });
  find(p, 'BUTTON', "Paste here: 'notes.txt'").fire('click');
  const [op] = progOps(ws);
  assert.deepEqual([op.action, op.targets, op.params], ['paste', [DL], clip]);
  sent(ws, op.k, { a: 'sent', result: { path: `${DL}/notes.txt` } });
  assert.deepEqual(queuedOps(ws), [], 'no clear of the page\'s own: one place clears, the PC core');
  // the core's clear arrives as the record's change: the Paste button leaves with the clip
  ws.frame({ t: 'chg', scope: 'files', epoch: 3, seq: 41, patches: [{ addr: 'rec.files.clip', set: { v: null, rev: 4, by: 'pc', at: 2 } }] });
  assert.equal(findAll(p, 'BUTTON').some((b) => /^Paste here/.test(b.textContent || '')), false);
});

test('Sort and Hidden are shared rows: a record op expecting the value seen; the rows reorder at once', async () => {
  const { p, ws, rows } = await page();
  const sort = byKey(p, 'sort');
  sort.value = 'size';
  sort.fire('change', { target: sort });
  const [op] = queuedOps(ws);
  assert.deepEqual([op.window, op.action, op.targets, op.result, op.expect], ['files', 'sort', ['sort'], 'size', { value: 'name' }]);
  const biggest = rows.filter((r) => !r.dir && !r.name.startsWith('.')).sort((a, b) => b.size - a.size)[0];
  const firstFile = all(main(p)).filter((e) => e.tagName === 'LI' && e.getAttribute('data-name') !== null && !e.classList.contains('dir'))[0];
  assert.equal(firstFile.getAttribute('data-name'), biggest.name);
  const hid = byKey(p, 'hidden');
  hid.checked = true;
  hid.fire('change', { target: hid });
  assert.deepEqual(queuedOps(ws).at(-1).result, true);
});

test('several selected: Move to Trash is one op over their paths; Download gives one link per file and leaves folders out', async () => {
  const { p, ws, rows } = await page();
  const files = rows.filter((r) => !r.dir && !r.name.startsWith('.')).slice(0, 2);
  const dir = rows.find((r) => r.dir);
  for (const r of [...files, dir]) byKey(p, `sel:${r.name}`).fire('change');
  assert.match(main(p).textContent, /3 selected/);
  byKey(p, 'sel:dl').fire('click');
  const links = all(main(p)).filter((e) => e.tagName === 'A' && /\?dl=1$/.test(e.getAttribute('href') || ''));
  assert.deepEqual(links.map((a) => a.getAttribute('download')), files.map((r) => r.name));
  assert.match(main(p).textContent, /1 folder is left out: a folder is not downloaded/);
  find(p, 'BUTTON', 'Move to Trash').fire('click');
  assert.match(dialog(p).textContent, /Delete 3 items\?/);
  dialog(p).querySelector('button.act').fire('click');
  await settle();
  assert.deepEqual(progOps(ws)[0].targets.sort(), [...files, dir].map((r) => `${DL}/${r.name}`).sort());
});

test('a selection is what the folder shows: a dotfile selected leaves it when Hidden is turned off elsewhere', async () => {
  const rows = folderRows(40);
  const dot = rows.find((r) => r.name.startsWith('.'));
  const { p, ws } = await page({ record: filesRecord({ 'rec.files.hidden': { v: true, rev: 2, by: 'phone-1', at: 1 } }), rows });
  byKey(p, `sel:${dot.name}`).fire('change');
  assert.match(main(p).textContent, /1 selected/);
  ws.frame({ t: 'chg', scope: 'files', epoch: 3, seq: 41, patches: [{ addr: 'rec.files.hidden', set: { v: false, rev: 3, by: 'phone-1', at: 2 } }] });
  assert.doesNotMatch(main(p).textContent, /selected/);
});

test('a text file\'s pane reads its first chunk at 0 and shows it', async () => {
  const { p, ws, rows } = await page();
  const r = rows.find((x) => !x.dir && x.name.endsWith('.txt'));
  p.go(at(`path:${DL}/${r.name}`));
  const scope = `files/text:${DL}/${r.name}/at:0`;
  assert.ok(subs(ws).some((f) => f.scope === scope));
  ws.frame({ t: 'snap', scope, desc: { text: 'first line\nsecond line', more: false, totalBytes: 22, bytesRead: 22 } });
  assert.equal(find(p, 'PRE').textContent, 'first line\nsecond line');
  assert.match(main(p).textContent, /The end \(22 B\)\./);
});

test('the job line shows the PC\'s job with its age; the slot\'s actions are dim while it runs', async () => {
  const { p } = await page({ desc: filesDesc({ jobs: [runningCopy()] }) });
  assert.match(main(p).textContent, /On the PC: Paste 'harbour photos' · copying · 42% · 3 m/);
  const mk = find(p, 'BUTTON', 'New folder');
  assert.ok(mk.classList.contains('dim'));
  assert.match(mk.getAttribute('data-why'), /busy — one operation at a time/);
});

test('at a phone\'s width an entry\'s address still reads its folder until the entry is held (seen in headless Firefox)', async () => {
  const rows = folderRows(40);
  const r = rows.find((x) => !x.dir && x.name.endsWith('.txt'));
  const { p, ws } = await page({ path: at(`path:${DL}/${r.name}`), rows: null, narrow: true });
  assert.ok(subs(ws).some((f) => f.scope === `files/folder:${DL}/page:0`), 'the folder is asked');
  ws.frame({ t: 'snap', scope: `files/folder:${DL}/page:0`, desc: folderPage(rows, 0) });
  assert.ok(find(p, 'H2', r.name));
  assert.ok(find(p, 'BUTTON', '← Back'), 'Back, at a phone\'s width');
});

test('a job this page started that the slot no longer names after a reconnect is said, never left waiting', async () => {
  const { p, ws, timers, stream, rows } = await page();
  const r = rows.find((x) => !x.dir);
  p.go(at(`path:${DL}/${r.name}`));
  find(p, 'BUTTON', 'Stats').fire('click');
  sent(ws, progOps(ws)[0].k, { a: 'sent', result: { job: 'j4' } });
  p.sockets[0].close();
  timers.timeouts.filter((x) => x.live && x.ms === 2000).at(-1).fn();
  const ws2 = p.sockets.at(-1);
  ws2.open();
  ws2.frame(welcome(stream, { conn: 8 }));
  ws2.frame({ t: 'snap', scope: 'desc/files', desc: filesDesc({ jobs: [] }) });
  assert.match(p.said().at(-1), /Stats: not known how it ended — the connection to the PC ended while it ran/);
});
