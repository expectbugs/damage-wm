// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-2, package Q5w: the Files page's pure model. The pins: an address names a folder or an entry by its
// trailing slash; the shared hidden row drops a dotfile at once and folders come first; a folder's pages are asked
// until an entry the address names is held; a text's next chunk starts where the PC stopped reading, not where its
// decoded text ends, and a chunk that consumed nothing ends the text; delete forever always asks twice; a pasted cut
// clears the clipboard only if it still holds what the paste took; blob ids are base64url of the path's UTF-8; one
// upload at a time and a stopped list sends nothing more; a job that has not moved says so beside its age.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as M from '../../main/resources/web/js/files-model.mjs';
import { folderRows, folderPage } from './files-fixtures.mjs';

test('an address names a folder by its trailing slash and an entry without one; the anchor is an entry name', () => {
  assert.deepEqual(M.placeOf('path:/home/ada/Downloads/', '@notes.txt'), { at: 'folder', dir: '/home/ada/Downloads', focus: 'notes.txt' });
  assert.deepEqual(M.placeOf('path:/home/ada/Downloads/Salt Road'), { at: 'entry', dir: '/home/ada/Downloads', name: 'Salt Road' });
  assert.deepEqual(M.placeOf('path:/'), { at: 'folder', dir: '/', focus: null });
  assert.deepEqual(M.placeOf('trash'), { at: 'trash' });
  assert.equal(M.placeOf(null).at, 'locations');
  assert.equal(M.placeOf('path:relative/x').bad, 'relative/x');
  assert.equal(M.folderTarget('/home/ada'), 'path:/home/ada/');
  assert.equal(M.entryTarget('/', 'etc'), 'path:/etc');
});

test('breadcrumbs start at the deepest location that holds the folder', () => {
  const locs = [{ path: '/home/ada', label: 'Home' }, { path: '/home/ada/Downloads', label: 'Downloads' }, { path: '/', label: 'Root' }];
  assert.deepEqual(M.crumbs('/home/ada/Downloads/films/old', locs).map((c) => [c.label, c.path]),
    [['Downloads', '/home/ada/Downloads'], ['films', '/home/ada/Downloads/films'], ['old', '/home/ada/Downloads/films/old']]);
  assert.deepEqual(M.crumbs('/etc', [{ path: '/home/ada', label: 'Home' }]).map((c) => c.path), ['/', '/etc']);
  assert.equal(M.folderLabel('/etc/x', []), '/etc/x');
  assert.equal(M.folderLabel('/home/ada/a', locs), 'Home / a');
});

test('the shared rows decide the order and the filter: folders first, a dotfile gone at once with hidden off', () => {
  const rows = [{ name: 'b.txt', size: 5, mtime: 1 }, { name: '.cfg', size: 1, mtime: 3 }, { name: 'Zeta', dir: true, size: 0, mtime: 0 }, { name: 'a.txt', size: 9, mtime: 2 }];
  assert.deepEqual(M.present(rows, 'name', false).map((r) => r.name), ['Zeta', 'a.txt', 'b.txt']);
  assert.deepEqual(M.present(rows, 'name', true).map((r) => r.name), ['Zeta', '.cfg', 'a.txt', 'b.txt']);
  assert.deepEqual(M.present(rows, 'size', false).map((r) => r.name), ['Zeta', 'a.txt', 'b.txt']);
  assert.deepEqual(M.present(rows, 'mtime', true).map((r) => r.name), ['Zeta', '.cfg', 'a.txt', 'b.txt']);
});

test('what an entry previews as', () => {
  assert.deepEqual(['x.JPG', 'y.pdf', 'z.epub', 'notes.md', 'README', 'film.mkv', '.bashrc'].map((name) => M.kindOf({ name })),
    ['image', 'pdf', 'epub', 'text', 'text', 'other', 'other']);
  assert.equal(M.kindOf({ name: 'a.jpg', dir: true }), 'dir');
});

test('a folder\'s pages are asked one at a time, and until an entry the address names is held', () => {
  const rows = folderRows(1200);
  const l = new M.FolderListing('/home/ada/Downloads/');
  assert.equal(l.src, '/home/ada/Downloads');
  assert.equal(l.scope(0), 'files/folder:/home/ada/Downloads/page:0');
  assert.equal(l.pageOf('files/folder:/home/ada/Downloads/page:2'), 2);
  assert.equal(l.pageOf('files/folder:/home/ada/Downloads/x/page:2'), null);
  l.want = rows[1100].name;
  assert.equal(l.next(false, 0), 0);
  l.ask(0);
  assert.equal(l.next(false, 0), null, 'one page in flight');
  l.got(0, folderPage(rows, 0));
  assert.equal(l.next(false, 0), 1, 'not near the end, but the named entry is not held yet');
  l.ask(1);
  l.got(1, folderPage(rows, 1));
  assert.equal(l.next(false, 0), 2);
  l.ask(2);
  l.got(2, folderPage(rows, 2));
  assert.ok(l.find(rows[1100].name));
  assert.equal(l.next(false, 0), null, 'held, and the end');
  const m = new M.FolderListing('/x');
  m.ask(0);
  m.got(0, folderPage(rows, 0));
  assert.equal(m.next(false, 0), null, 'nothing named: only near the end');
  assert.equal(m.next(true, 0), 1);
});

test('a text\'s next chunk starts where the PC stopped reading; a chunk that consumed nothing ends it', () => {
  const t = new M.TextDoc('/home/ada/a.txt');
  assert.equal(t.next(false, 0), 0);
  t.ask(0);
  assert.equal(t.scope(0), 'files/text:/home/ada/a.txt/at:0');
  // three bytes of UTF-8 decoded to one character: the offset follows the bytes
  t.got(0, { text: '€abc', more: true, totalBytes: 100, bytesRead: 6 });
  assert.equal(t.next(true, 0), 6);
  t.ask(6);
  t.got(6, { text: '', more: true, totalBytes: 100, bytesRead: 0 });
  assert.equal(t.next(true, 0), null, 'the same bytes are not asked again');
  assert.deepEqual(t.status(), { kind: 'stuck', at: 6 });
  t.refused(6, { code: 'retry', reason: 'the disk is busy' }, 1000);
  assert.equal(t.next(true, 5999), null);
  assert.equal(t.next(true, 6000), 6);
});

test('delete forever always asks twice and names the thing; to the trash asks once', () => {
  assert.deepEqual(M.confirmFor('purge', ['old notes.txt']).double, true);
  assert.match(M.confirmFor('purge', ['old notes.txt']).title, /Delete 'old notes\.txt' forever\?/);
  assert.match(M.confirmFor('purge', ['a', 'b', 'c']).title, /Delete 3 items forever\?/);
  const t = M.confirmFor('trash', ['x.pdf']);
  assert.deepEqual([t.title, t.act, t.double], ["Delete 'x.pdf'?", 'Move to Trash', false]);
  assert.equal(M.confirmFor('copy', ['x']), null);
});

test('the clipboard, sort and hidden are registers expecting the value seen; a pasted cut clears only its own clip', () => {
  const items = new Map([['rec.files.clip', { v: { verb: 'cut', path: '/a/b' }, rev: 2 }]]);
  const rec = { item: (w, n) => items.get(`rec.${w}.${n}`), value: (w, n) => (items.get(`rec.${w}.${n}`) || {}).v };
  assert.deepEqual(M.clipDraft('copy', '/a/c', rec), { window: 'files', action: 'copy', targets: ['clip'], result: { verb: 'copy', path: '/a/c' }, expect: { value: { verb: 'cut', path: '/a/b' } } });
  assert.deepEqual(M.regDraft('sort', 'sort', 'size', rec).expect, { value: null });
  assert.deepEqual(M.clipClearDraft({ verb: 'cut', path: '/a/b' }), { window: 'files', action: 'cut', targets: ['clip'], result: null, expect: { value: { verb: 'cut', path: '/a/b' } } });
  assert.deepEqual(M.clipOf(rec), { verb: 'cut', path: '/a/b' });
  assert.equal(M.sortOf(rec), 'name');
  assert.equal(M.hiddenOf(rec), false);
});

test('a blob id is the path\'s UTF-8 in base64url without padding; a download adds dl=1', () => {
  assert.equal(M.b64url('/a/ü?'), Buffer.from('/a/ü?').toString('base64url'));
  assert.equal(M.b64url('/home/ada/x>>'), Buffer.from('/home/ada/x>>').toString('base64url'));
  assert.equal(M.blobUrl('/a', { dl: true }), `/blob/f.${Buffer.from('/a').toString('base64url')}?dl=1`);
  assert.equal(M.pdfPageUrl('/a', 2, 1024), `/blob/f.${Buffer.from('/a').toString('base64url')}?r=pdf%3A2%3A1024`);
  assert.deepEqual([M.pdfWidth(390, 3), M.pdfWidth(600, 1), M.pdfWidth(10)], [1280, 768, 256]);
  assert.equal(M.pdfWidth(4000, 2), 2048);
});

test('one upload at a time; a stopped list sends nothing more; a taken name is known at the drop', () => {
  const u = new M.Uploads();
  const [a, b, c] = u.add([{ name: 'a.txt', size: 3 }, { name: 'b.txt', size: 4 }, { name: 'c.txt', size: 5 }], '/d/', new Set(['b.txt']));
  assert.deepEqual([a.dest, a.clash, b.clash], ['/d', false, true]);
  assert.equal(u.next(), a);
  a.state = 'sending';
  assert.equal(u.next(), null, 'one at a time');
  assert.equal(u.sending, true);
  a.state = 'done';
  assert.equal(u.next(), b);
  assert.equal(u.stopRest('not sent: the PC does not answer'), 2);
  assert.equal(u.next(), null);
  assert.deepEqual([b.state, c.reason], ['unsent', 'not sent: the PC does not answer']);
  assert.equal(M.uploadsHead(u.items), 'Uploads: 1 uploaded · 2 not sent');
  u.clearDone();
  assert.equal(u.items.length, 0);
  assert.equal(M.freeName('a.txt', new Set(['a.txt', 'a (2).txt'])), 'a (3).txt');
  assert.equal(M.freeName('notes', new Set(['notes'])), 'notes (2)');
});

test('the PC\'s answer to an upload\'s body in words: an existing name is never replaced, a full disk is said', () => {
  assert.deepEqual(M.putAnswer(201, ''), { ok: true });
  assert.match(M.putAnswer(409, 'x exists').reason, /already there; the PC never replaces one \(x exists\)/);
  assert.match(M.putAnswer(507, '2 GB needed').reason, /not enough space on the PC: 2 GB needed/);
});

test('the job slot\'s line: its age on the PC\'s clock, and how long it has not moved', () => {
  const j = { id: 'j1', label: "Stats of 'archive'", state: 'running', phase: 'counting', startedAt: 1000, updatedAt: 2000 };
  assert.equal(M.jobLine(j, 5000), "On the PC: Stats of 'archive' · counting · 4 s");
  assert.equal(M.jobLine(j, 200000), "On the PC: Stats of 'archive' · counting · 3 m · no progress for 3 m");
  assert.equal(M.jobLine({ ...j, fraction: 0.5, state: 'queued' }, 5000), "On the PC: Stats of 'archive' · waiting for the slot · counting · 50% · 4 s");
  assert.match(M.busyWhy({ jobs: [j] }), /busy — one operation at a time/);
  assert.equal(M.busyWhy({ jobs: [{ ...j, state: 'done' }] }), null);
  assert.equal(M.jobOutcome('Paste', { state: 'failed', answer: 'no space left' }), 'Paste: failed — no space left');
  assert.equal(M.jobOf({ a: 'sent', result: { job: 7 } }), '7');
});

test('a new name: none, a slash, its own name, or one already there is refused in words', () => {
  const taken = new Set(['a.txt']);
  assert.equal(M.nameWhy('  ', taken), 'A name is needed.');
  assert.equal(M.nameWhy('x/y', taken), "A name cannot hold '/'.");
  assert.equal(M.nameWhy('a.txt', taken, 'b.txt'), "'a.txt' is already in this folder.");
  assert.equal(M.nameWhy('b.txt', taken, 'b.txt'), 'That is its name already.');
  assert.equal(M.nameWhy('c.txt', taken), null);
});
