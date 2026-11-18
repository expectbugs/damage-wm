// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-2, package JW0: the stand-in's fixtures, in the shapes the PC sends (ReaderCore.describe / level, EpubDoc's
// parts, PcScopes' Main, DevicesCore, the diagnosis), and the scenes the look harness pictures. Every title, name,
// device and address here is made up (addresses from the documentation range, RFC 5737).

import { torrentsDesc, torrentsScope, torrentsRecord, torrentsSeries } from './torrents-fixtures.mjs';
import { filesDesc, filesScope, filesRecord, folderRows, runningCopy, DL, EMPTY } from './files-fixtures.mjs';
import { feedScope, feedRecord, feedBlobs, feedChange, itemId, F_NOW } from './feed-fixtures.mjs';
import { tmuxScope, tmuxRecord, tmuxLive, streamData, OUTPUT } from './tmux-fixtures.mjs';
import { format } from '../../main/resources/web/js/address.mjs';

const ME = 'web-a1b2c3';
const NOW = Date.UTC(2026, 10, 15, 14, 2);

const book = (id, title, author, extra = {}) => ({ id, title, author, bytes: 300000, folder: '', fraction: null, length: 3000, ...extra });
const BOOKS = [
  book('b1', 'The Salt Road', 'Ada Marlow', { cover: 'cover-b1' }),
  book('b2', 'Lantern Weather', 'Bea Okafor'),
  book('b3', 'A Map of Small Rivers, with Notes on the Ferries That Cross Them and the People Who Keep Them', 'Cai Ferreira', { cover: 'cover-b3' }),
  book('b4', 'Night Ferry', 'Dov Lindqvist'),
  book('b5', 'Broken Spine', 'Eli Varga', { unreadable: 'the file is not a zip archive' }),
  book('b6', 'Copper Hill', 'Fen Adeyemi', { cover: 'cover-b6' }),
];

const PROSE = [
  'The road out of the salt flats ran straight for a day and a half, and then it forgot what it was for.',
  'Nobody in the caravan said so. They watched the posts lean one way and then the other, and kept walking.',
  'By evening the wind had turned and carried the smell of the sea, which was still three days away.',
];

/** Blocks from specs, with their character ranges laid end to end from `start`. */
function blocks(start, specs) {
  let at = start;
  return specs.map((s, i) => {
    const len = s.spans ? s.spans.reduce((n, x) => n + x.text.length, 0) : 1;
    const b = { id: `b${at}`, kind: s.kind || 'para', charStart: at, charEnd: at + len, spans: s.spans || [], ...(s.level ? { level: s.level } : {}), ...(s.ref ? { ref: s.ref } : {}) };
    at += len + (i < specs.length - 1 ? 1 : 0);
    return b;
  });
}
const p = (...spans) => ({ spans: spans.map((x) => (typeof x === 'string' ? { text: x, marks: [] } : { marks: [], ...x })) });

function part(n) {
  const start = n * 1000;
  const specs = n === 0 ? [
    { kind: 'heading', level: 1, ...p('One: the flats') },
    p(PROSE[0], ' ', { text: 'It was the second dry year.', marks: ['em'] }),
    p('The old survey (see ', { text: 'the survey office’s notes', href: 'https://example.org/survey/notes?year=2' }, ') said the posts were set every ', { text: 'forty paces', marks: ['strong'] }, ', and ', { text: 'the second chapter', jump: '@c1000.1~' }, ' says why they lean.'),
    { kind: 'image', ref: 'fig-1' },
    p(PROSE[1]),
    { kind: 'quote', ...p('Walk until the posts stop leaning, then walk a little more.') },
    { kind: 'item', level: 1, ...p('water, two skins each') },
    { kind: 'item', level: 2, ...p('salt for the animals, which sounds strange out here') },
    { kind: 'mono', ...p('post 112   leans east   12 deg\npost 113   leans west    4 deg') },
    { kind: 'rule' },
    p(PROSE[2]),
    p(PROSE.join(' ')),
    p(PROSE.slice().reverse().join(' ')),
  ] : [{ kind: 'heading', level: 1, ...p(n === 1 ? 'Two: the posts' : 'Three: the coast') }, p(PROSE.join(' ')), p(PROSE[1])];
  return { kind: 'document', id: `book:b1/part:${n}`, extractor: 1, part: n, parts: 3, title: specs[0].spans[0].text, charStart: start, charEnd: start + 999, blocks: blocks(start, specs) };
}

const CHAPTERS = ['One: the flats', 'Two: the posts', 'Three: the coast'].map((title, i) => ({ title, anchor: `@c${i * 1000}.1` }));

/** S1-2 Q3w: a notice item in the spec's §2.1 shape (made-up torrent names and hashes). */
const notice = (hash, occ, body, state = 'RAISED', at = NOW) => [`rec.notices.torrents:${hash}`, { by: 'pc', at, v: {
  occ, raisedAt: at, app: 'torrents', source: 'torrents', body, urgency: 'NORMAL', ackRequired: false, gatedBy: 'Torrents',
  target: `t:${hash}`, actions: [], state, origin: 'pc', mayRepeat: false } }];
const RAISES = [
  ['c0ffee01', 'done · Lantern Weather (2026) 1080p'],
  ['c0ffee02', 'error · Night.Ferry.S01E03.720p.WEB'],
  ['c0ffee03', 'done · A.Map.of.Small.Rivers.with.Notes.on.the.Ferries.That.Cross.Them.2026.2160p.WEB-DL.DDP5.1'],
];

export const fixtures = {
  code: 'K7M4QX',
  // S1-2 Q4w: Torrents' scopes that are not fixed (details, listings, pages) are answered by function; Q5w: Files' too
  scope: (s) => torrentsScope(s) || filesScope(s) || feedScope(s) || tmuxScope(s),
  records: { torrents: torrentsRecord, files: () => filesRecord(), feed: () => feedRecord(), tmux: () => tmuxRecord() },
  // S1-3: a live source's messages (Tmux's frames and its terminal stream)
  live: (scope, scene) => tmuxLive(scope, scene),
  // S1-3: Feed's pictures, drawn by the fixtures (comics, an archive page, an article's chart)
  blob: (id) => feedBlobs()[id],
  // S1-3: what another device or a session does while a page is open (the stand-in's `/__push?name=`)
  push: (name) => ({
    // the glasses read on in the open article (block 6) and in the archive (episode 420), later than this page's input
    'feed-place': [feedChange(41, 'place', { item: itemId('news', 0), b: 6, o: 0, v: 1 }, F_NOW + 3600e3)],
    'feed-binge': [feedChange(42, 'binge.quest', { ep: 420, f: 0, s: 0, w: 564 }, F_NOW + 3600e3)],
    // the shell answers what was typed
    'tmux-output': [{ t: 'live', src: 'live/tmux/stream/workstation/shell', data: streamData(OUTPUT) }],
  }[name] || []),
  // S1-2 Q5w: an upload op is answered with an id for its body
  program: (op) => (op.window === 'files' && op.action === 'upload' ? { a: 'sent', result: { upload: `u-${op.k}` } } : null),
  blobs: { 'cover-b1': [400, 600, [122, 75, 58]], 'cover-b3': [600, 400, [49, 80, 110]], 'cover-b6': [300, 600, [79, 107, 60]], 'fig-1': [1600, 900, [130, 110, 70]] },
  welcome: (stream, scene = {}) => ({ t: 'welcome', proto: 3, device: ME, level: scene.level || 'full', conn: 7, pcClock: NOW, epoch: 3, seq: 40,
    // S1-3: a scene may be an older PC's (a build that names no Feed contract)
    contracts: scene.contracts || { reader: 1, notices: 1, torrents: 1, files: 1, feed: 1, tmux: 1 }, bundle: { hash: 'look' } }),
  // the notice record at first contact (adopted, nothing pops), and `n` raised after it, one change each
  notices: () => ({ t: 'snap', scope: 'notices', epoch: 3, seq: 40, items: Object.fromEntries([
    notice('a1d00001', 1, 'done · Copper Hill', 'DISMISSED', NOW - 86400e3), notice('a1d00002', 2, 'done · Salt Road', 'RAISED', NOW - 3600e3)]) }),
  raise: (n) => RAISES.slice(0, n).map(([hash, body], i) => ({ t: 'chg', scope: 'notices', epoch: 3, seq: 41 + i,
    origin: { device: 'pc', stream: 'notices', n: 3 + i }, patches: [notice(hash, 3 + i, body, 'RAISED', NOW - (n - i) * 60e3)].map(([addr, set]) => ({ addr, set })) })),
  record: () => ({ t: 'snap', scope: 'reader', epoch: 3, seq: 40, items: {
    'rec.reader.place.b1': { v: '@c120.1~The old survey', by: 'phone-1', at: NOW - 3600e3 },
    'rec.reader.place.b6': { v: '@c2400.1~', by: ME, at: NOW - 86400e3 },
    'rec.reader.recent': { v: 'b1', by: 'phone-1', at: NOW - 3600e3 },
  } }),
  descs: {
    'desc/main': { cards: [{ window: 'reader', name: 'Reader', icon: 'book', min: 'view', availability: 'live',
      summary: { book: 'b1', line: 'The Salt Road · 4%', progress: 0.04 } },
    { window: 'torrents', name: 'Torrents', icon: 'torrents', min: 'view', availability: 'live',
      summary: { line: '6 downloading · 3.1 MB/s', detail: '28 seeding · 4 stopped · 4 errors', progress: 0.71 } },
    { window: 'files', name: 'Files', icon: 'folder', min: 'full', availability: 'live', summary: { line: '5 locations · 1.1 TB free in Home' } },
    { window: 'feed', name: 'Feed', icon: 'feed', min: 'view', availability: 'live', summary: { line: '25 new · front page 12 · tech news 4', detail: '1 failing · 1 rate-limited' } },
    { window: 'tmux', name: 'Tmux', icon: 'terminal', min: 'full', availability: 'live', summary: { line: '6 sessions · 1 waiting', detail: 'build wants input' } }],
    who: { [ME]: 'desk browser', 'phone-1': 'phone' } },
    'desc/reader': { id: 'reader', name: 'Reader', min: 'view', state: 'ok', shelf: { folder: '', folders: ['Essays', 'Series with a long folder name'], books: BOOKS },
      actions: [{ id: 'open', kind: 'r', level: 'view', label: 'Open' }, { id: 'place', kind: 'r', level: 'view', label: 'Place' },
        { id: 'bookmark', kind: 'r', level: 'control', label: 'Bookmark' }, { id: 'reset', kind: 'i', level: 'control', label: 'Reset progress' }] },
    'desc/torrents': torrentsSeries(),
    'desc/files': filesDesc(),
    'reader/book:b1': { ...BOOKS[0], chapters: CHAPTERS },
    'reader/book:b1/part:0': part(0),
    'reader/book:b1/part:1': part(1),
    'reader/book:b1/part:2': part(2),
    devices: { min: 'full',
      rows: [
        { id: ME, name: 'desk browser', kind: 'web', level: 'full', tailnetName: 'study-pc', approvedAt: NOW - 5 * 86400e3, approvedBy: 'the PC', lastSeen: NOW },
        { id: 'phone-1', name: 'phone', kind: 'phone', level: 'full', address: '192.0.2.20', approvedAt: NOW - 30 * 86400e3, approvedBy: 'the PC', lastSeen: NOW - 3600e3 },
        { id: 'web-d4e5f6', name: 'browser on Android (phone)', kind: 'web', level: 'view', address: '192.0.2.31', approvedAt: NOW - 2 * 86400e3, approvedBy: ME, lastSeen: 0 },
      ],
      pending: [{ code: 'R2D9KP', name: 'browser on Linux', kind: 'web', tailnetName: 'kitchen-tv', since: NOW - 120e3, replaces: 'web-d4e5f6' }],
      actions: ['approve', 'deny', 'rename', 'revoke'].map((id) => ({ id, kind: id === 'revoke' ? 'i' : 'r', level: 'full' })) },
    diag: { build: 'look 0.0', epoch: 3, seq: 40, contracts: { reader: 1 }, connections: [`${ME} · conn 7 · web`, 'phone-1 · conn 5 · app lane'],
      log: ['14:01:58 web listener: a page connected (conn 7)', '14:02:00 record: seq 40 written', '14:02:01 reader: the shelf was read (6 books, one unreadable: the file is not a zip archive)'] },
  },
};

/** The scenes: the page's address, what the stand-in answers the hello with, and the steps before the picture. */
export const SCENES = {
  waiting: { path: '/', hello: 'pending', steps: [{ wait: '.pair .pcode' }] },
  closed: { path: '/', hello: 'denied', steps: [{ wait: '.pair button' }] },
  home: { path: '/', steps: [{ wait: '.cards .card' }] },
  shelf: { path: '/w/reader', steps: [{ wait: '.shelf .book' }] },
  book: { path: '/w/reader/book:b1', steps: [{ wait: '.text figure' }] },
  chapters: { path: '/w/reader/book:b1', steps: [{ wait: '.text figure' }, { click: '.side-toggle' }] },
  devices: { path: '/w/devices', steps: [{ wait: '.devices .row-item' }] },
  away: { path: '/w/reader', steps: [{ wait: '.shelf .book' }, { fetch: '/__away' }, { wait: '#reach:not([hidden])' }] },
  settings: { path: '/w/settings', steps: [{ wait: '.looks' }] },
  diag: { path: '/w/diag', steps: [{ wait: '.facts' }] },
  // S1-2 Q3w: notices raised while a page is open, as toasts over the shelf and over a book's text
  'notice-shelf': { path: '/w/reader', steps: [{ wait: '.shelf .book' }, { fetch: '/__raise?n=1' }, { wait: '.toast.notice' }] },
  'notices-shelf': { path: '/w/reader', steps: [{ wait: '.shelf .book' }, { fetch: '/__raise?n=3' }, { wait: '.toast.notice + .toast.notice + .toast.notice' }] },
  'notice-book': { path: '/w/reader/book:b1', steps: [{ wait: '.text figure' }, { fetch: '/__raise?n=1' }, { wait: '.toast.notice' }] },
  'notices-book': { path: '/w/reader/book:b1', steps: [{ wait: '.text figure' }, { fetch: '/__raise?n=3' }, { wait: '.toast.notice + .toast.notice + .toast.notice' }] },
  'notices-away': { path: '/w/reader', steps: [{ wait: '.shelf .book' }, { fetch: '/__raise?n=2' }, { wait: '.toast.notice + .toast.notice' },
    { fetch: '/__away' }, { wait: '#reach:not([hidden])' }] },
  // S1-2 Q4w: Torrents — forty transfers in mixed states, an empty table, a search with results and one with none, a
  // torrent's page, the details, a selection, its confirm, browse, the account, out of reach
  'torrents-table': { path: '/w/torrents', steps: [{ wait: '.ttable tbody tr, .tcards li' }] },
  'torrents-empty': { path: '/w/torrents', descs: { 'desc/torrents': torrentsDesc({ n: 0, samples: 0 }) }, steps: [{ wait: '.tbody .note' }] },
  'torrents-week': { path: '/w/torrents', steps: [{ wait: '.ttable tbody tr, .tcards li' }, { click: '[data-key="filter:week"]' }, { wait: '.chip.current' }] },
  'torrents-details': { path: `/w/torrents/t:${torrentsDesc().transfers[0].id}`, steps: [{ wait: '.td-files li' }] },
  'torrents-selected': { path: '/w/torrents', steps: [{ wait: '.ttable tbody tr, .tcards li' },
    { click: '[data-key="selecting"], tbody tr:nth-child(1) input' }, { click: '.tcard:nth-child(2) .tc-main, tbody tr:nth-child(2) input' },
    { click: '.tcard:nth-child(4) .tc-main, tbody tr:nth-child(4) input' }, { wait: '.tbulk' }] },
  'torrents-confirm': { path: '/w/torrents', steps: [{ wait: '.ttable tbody tr, .tcards li' },
    { click: '[data-key="selecting"], tbody tr:nth-child(1) input' }, { click: '.tcard:nth-child(2) .tc-main, tbody tr:nth-child(2) input' },
    { click: '.tbulk button.danger' }, { wait: 'dialog[open]' }] },
  'torrents-browse': { path: '/w/torrents/browse', steps: [{ wait: '.tcat' }] },
  'torrents-listing': { path: '/w/torrents/listing:newest', steps: [{ wait: '.ttable.tl tbody tr, .tcards li' }] },
  'torrents-search': { path: '/w/torrents/search:salt', steps: [{ wait: '.ttable.tl tbody tr, .tcards li' }] },
  'torrents-none': { path: '/w/torrents/search:zzzz', steps: [{ wait: '.tend' }] },
  'torrents-page': { path: '/w/torrents/tl:240001', steps: [{ wait: '.nfo' }] },
  'torrents-settings': { path: '/w/torrents', steps: [{ wait: '.ttable tbody tr, .tcards li' }, { click: '.tset summary' }, { wait: '.tset[open] select' }] },
  'torrents-account': { path: '/w/torrents/account', steps: [{ wait: '.facts' }] },
  // S1-2 Q5w: Files — the locations, a folder of two hundred entries with long names (list and grid), an empty folder,
  // the trash, a text, a picture and a PDF in the pane, an upload in progress, a running job, a selection, Delete
  // forever's confirm, a device below full, out of reach
  ...filesScenes(),
  ...feedScenes(),
  ...tmuxScenes(),
  'torrents-away': { path: '/w/torrents', steps: [{ wait: '.ttable tbody tr, .tcards li' }, { fetch: '/__away' }, { wait: '#reach:not([hidden])' }] },
};

function filesScenes() {
  const rows = folderRows(200);
  const pick = (ext) => rows.find((r) => !r.dir && r.name.endsWith(`.${ext}`) && r.name.length < 30).name;
  const f = (target) => format({ window: 'files', target });
  const listed = { wait: '.flist .frow, .fgrid .frow' };
  const UPLOADS = [{ name: 'harbour survey 2026.pdf', size: 64e6 }, { name: 'notes from the ferry.txt', size: 2e4 }, { name: 'a-third-file-with-a-very-long-name-from-the-camera-roll-2026-11-15.jpg', size: 3e6 }];
  return {
    'files-locations': { path: '/w/files', steps: [{ wait: '.flocs .floc' }] },
    'files-folder': { path: f(`path:${DL}/`), steps: [listed] },
    'files-grid': { path: f(`path:${DL}/`), steps: [listed, { click: '[data-key="mode"]' }, { wait: '.fgrid .frow' }] },
    'files-empty': { path: f(`path:${EMPTY}/`), steps: [{ wait: '.tend' }] },
    'files-trash': { path: f('trash'), steps: [{ wait: '.ftrash .frow' }] },
    'files-text': { path: f(`path:${DL}/${pick('txt')}`), steps: [{ wait: '.ftext' }] },
    'files-picture': { path: f(`path:${DL}/${pick('jpg')}`), steps: [{ wait: '.fimg' }] },
    'files-pdf': { path: f(`path:${DL}/${pick('pdf')}`), steps: [{ wait: '.fpage' }] },
    'files-upload': { path: f(`path:${DL}/`), hold: 'upload', steps: [listed, { drop: '.page.files', files: UPLOADS }, { wait: '.fu.op' }] },
    'files-upload-cut': { path: f(`path:${DL}/`), put: 'cut', steps: [listed, { drop: '.page.files', files: UPLOADS }, { wait: '.fu.cut' }] },
    'files-job': { path: f(`path:${DL}/`), descs: { 'desc/files': filesDesc({ jobs: [runningCopy({ startedAt: NOW - 190e3, updatedAt: NOW - 40e3 })] }) }, steps: [listed, { wait: '.fjob' }] },
    'files-selected': { path: f(`path:${DL}/`), steps: [listed, { click: '.frow:nth-child(6) input' }, { click: '.frow:nth-child(7) input' }, { click: '.frow:nth-child(9) input' }, { wait: '.tbulk' }] },
    'files-purge': { path: f('trash'), steps: [{ wait: '.ftrash .frow' }, { click: '.frow-acts button.danger' }, { wait: 'dialog[open]' }] },
    'files-below': { path: f(`path:${DL}/`), level: 'control', steps: [{ wait: '.page.files .note' }] },
    'files-away': { path: f(`path:${DL}/`), steps: [listed, { fetch: '/__away' }, { wait: '#reach:not([hidden])' }] },
  };
}

/** S1-3 (B17): Tmux — the sessions, each fixture frame (a shell, a full-screen editor, wide characters and box drawing,
 *  a program waiting with the quick keys; at a desk in the terminal from the stream's init, at a phone's width from the
 *  pictures), the history, typing into the terminal and its output, the typed line's, the paste's and the end's
 *  confirms, a burst of pictures, out of reach, a device below full, a desk whose PC serves no terminal library; the
 *  glass theme's versions of the frames. `tmux-typing` is a desk's scene (LOOK_SIZES=desk). */
function tmuxScenes() {
  const t = (target) => format({ window: 'tmux', target });
  // the grid's boxes at a phone's width, the terminal's rows at a desk's
  const drawn = { wait: '.tm-rows .tm-row .tm-s, .xterm-screen .xterm-row span' };
  // the theme as the Settings page sets it, after the page is drawn (the terminal draws itself again in its colours)
  const glass = (scene) => ({ ...scene, steps: [...scene.steps, { theme: 'glass' }] });
  const base = {
    'tmux-sessions': { path: '/w/tmux', steps: [{ wait: '.tm-list .tm-sess' }] },
    'tmux-shell': { path: t('session:workstation:shell'), steps: [drawn] },
    'tmux-editor': { path: t('session:workstation:editor'), steps: [drawn, { wait: '.tm-wl li' }] },
    'tmux-tables': { path: t('session:workstation:tables'), steps: [drawn] },
    'tmux-waiting': { path: t('session:workstation:build'), steps: [drawn, { wait: '.tm-prompt' }] },
    'tmux-history': { path: t('history:buildbox:logs'), steps: [{ wait: '.tm-term.hist .tm-row' }] },
  };
  const out = { ...base };
  for (const [k, v] of Object.entries(base)) out[`${k}-glass`] = glass(v);
  const field = '.xterm-helper-textarea';
  return {
    ...out,
    // his keys typed into the terminal go out as `raw` ops (the stand-in's log), and the session's answer is written
    'tmux-typing': { path: t('session:workstation:shell'), steps: [drawn, { focus: field }, { key: [field, 'm'] }, { key: [field, 'a'] },
      { key: [field, 'k'] }, { key: [field, 'e'] }, { key: [field, 'Enter'] }, { fetch: '/__push?name=tmux-output' }, { wait: '.tm-xterm.typing, .tm-xterm' }] },
    'tmux-type-confirm': { path: t('session:workstation:shell'), steps: [drawn, { type: '.tm-in', text: 'make package && ./run.sh --steep 4' }, { click: '.tm-type button' }, { wait: 'dialog[open]' }] },
    'tmux-paste-confirm': { path: t('session:workstation:shell'), steps: [drawn, { paste: '.tm-term.tm-xterm, .tm-in', text: 'cd ~/src/kettle\nmake clean\nmake package\n' }, { wait: 'dialog[open]' }] },
    'tmux-end-confirm': { path: t('session:workstation:shell'), steps: [drawn, { click: '.tm-acts button.danger' }, { wait: 'dialog[open]' }] },
    'tmux-end-act': { path: t('session:workstation:shell'), steps: [drawn, { click: '.tm-acts button.danger' }, { wait: 'dialog[open]' }, { click: 'dialog[open] button.act' }, { wait: '.tm-list .tm-sess' }] },
    'tmux-burst': { path: t('session:workstation:tables'), burst: 40, noXterm: true, steps: [drawn] },
    'tmux-away': { path: t('session:workstation:shell'), steps: [drawn, { fetch: '/__away' }, { wait: '#reach:not([hidden])' }] },
    'tmux-below': { path: '/w/tmux', level: 'control', steps: [{ wait: '.page.tmux .note' }] },
    'tmux-nolib': { path: t('session:workstation:shell'), noXterm: true, steps: [{ wait: '.tm-rows .tm-row .tm-s' }, { wait: '.tm-line.warn, .tm-line' }] },
  };
}

/** S1-3 (B17): Feed — the sources (one failing with its countdown, one rate-limited), a long list of items, an article
 *  (emphasis, a link, a picture, a code block), an article whose page did not extract, one still arriving, comments
 *  four deep and a flat list, a numbered comic at the latest (its bar's next and latest dim), a comic with its bonus
 *  panel, the archive as one page (a picture still being found), Flagged, mark all read's confirm and act, a read, a
 *  flag, the follow of another device's place (article and archive), browse, the settings, out of reach, a device at
 *  level view, and an older PC. */
function feedScenes() {
  const f = (target) => format({ window: 'feed', target });
  const rows = { wait: '.fd-rows .fd-row' };
  const art = { wait: '.fd-text figure img' };
  return {
    'feed-sources': { path: '/w/feed', steps: [{ wait: '.fd-s .fd-sh' }] },
    'feed-items': { path: f('src:front'), steps: [rows] },
    'feed-article': { path: f(`item:news:${itemId('news', 0)}`), steps: [art] },
    'feed-article-floor': { path: f(`item:news:${itemId('news', 1)}`), steps: [{ wait: '.fd-floor' }] },
    'feed-article-arriving': { path: f(`item:news:${itemId('news', 3)}`), silent: ['feed/item:'], steps: [{ wait: '.fd-wait' }] },
    'feed-comments': { path: f(`comments:news:${itemId('news', 0)}`), steps: [{ wait: '.fd-c.d4' }] },
    'feed-comments-flat': { path: f(`comments:front:${itemId('front', 0)}`), steps: [{ wait: '.fd-thread .fd-c' }] },
    'feed-comic': { path: f('num:stick:2961'), steps: [{ wait: '.fd-comic img' }] },
    'feed-comic-bonus': { path: f(`item:panel:${itemId('panel', 0)}`), steps: [{ wait: '.fd-bonus img' }] },
    'feed-binge': { path: f('binge:quest'), steps: [{ wait: '.fd-ep img' }, { wait: '.fd-pending' }] },
    // an address that names a place in the archive: the episode whose picture is still being found
    'feed-binge-pending': { path: format({ window: 'feed', target: 'binge:quest', anchor: '@e413.0.9' }), steps: [{ wait: '.fd-ep img' }, { wait: '.fd-pending' }] },
    'feed-flagged': { path: f('flagged'), steps: [rows] },
    'feed-markall': { path: f('src:front'), steps: [rows, { click: '.fd-acts button:nth-child(2)' }, { wait: 'dialog[open]' }] },
    'feed-browse': { path: f('src:front'), steps: [rows, { click: '.fd-browse summary' }, { wait: '.fd-browse[open] input' }, { type: '.fd-browse[open] input', text: 'knots' }] },
    'feed-settings': { path: f('settings'), steps: [{ wait: '.fd-settings select' }] },
    // acts: an opened row sends its source's read list and dims; Flag turns to Unflag; mark all read's act dims every row
    'feed-read': { path: f('src:front'), steps: [rows, { click: '.fd-row:nth-child(3) .fd-open' }, { wait: '.fd-text figure img, .fd-text .blk' }] },
    'feed-flag': { path: f(`item:news:${itemId('news', 0)}`), steps: [art, { click: '.fd-bar button' }, { wait: '.fd-bar button' }] },
    'feed-markall-act': { path: f('src:front'), steps: [rows, { click: '.fd-acts button:nth-child(2)' }, { wait: 'dialog[open]' }, { click: 'dialog[open] button.act' }] },
    // another device reads on in the article shown, and in the archive: the page follows by itself and sends nothing
    'feed-follow': { path: f(`item:news:${itemId('news', 0)}`), steps: [art, { fetch: '/__push?name=feed-place' }] },
    'feed-binge-follow': { path: f('binge:quest'), steps: [{ wait: '.fd-ep img' }, { fetch: '/__push?name=feed-binge' }, { wait: '.fd-ep[data-ep="420"] img' }] },
    'feed-away': { path: f(`item:news:${itemId('news', 0)}`), steps: [art, { fetch: '/__away' }, { wait: '#reach:not([hidden])' }] },
    'feed-view': { path: f(`item:news:${itemId('news', 0)}`), level: 'view', steps: [art] },
    'feed-old-pc': { path: '/w/feed', contracts: { reader: 1, notices: 1, torrents: 1, files: 1 }, steps: [{ wait: '.page h1' }] },
  };
}
