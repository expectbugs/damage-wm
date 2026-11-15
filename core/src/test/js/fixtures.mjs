// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-2, package JW0: the stand-in's fixtures, in the shapes the PC sends (ReaderCore.describe / level, EpubDoc's
// parts, PcScopes' Main, DevicesCore, the diagnosis), and the scenes the look harness pictures. Every title, name,
// device and address here is made up (addresses from the documentation range, RFC 5737).

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

export const fixtures = {
  code: 'K7M4QX',
  blobs: { 'cover-b1': [400, 600, [122, 75, 58]], 'cover-b3': [600, 400, [49, 80, 110]], 'cover-b6': [300, 600, [79, 107, 60]], 'fig-1': [1600, 900, [130, 110, 70]] },
  welcome: (stream) => ({ t: 'welcome', proto: 2, device: ME, level: 'full', conn: 7, pcClock: NOW, epoch: 3, seq: 40, highWater: { [stream]: 12 },
    contracts: { reader: 1 }, bundle: { version: 'look', hash: 'look', protoRange: [2, 2] } }),
  record: () => ({ t: 'snap', scope: 'reader', epoch: 3, seq: 40, colls: {}, items: {
    'rec.reader.place.b1': { v: '@c120.1~The old survey', rev: 4, by: 'phone-1', at: NOW - 3600e3 },
    'rec.reader.place.b6': { v: '@c2400.1~', rev: 2, by: ME, at: NOW - 86400e3 },
    'rec.reader.recent': { v: 'b1', rev: 9, by: 'phone-1', at: NOW - 3600e3 },
  } }),
  descs: {
    'desc/main': { cards: [{ window: 'reader', name: 'Reader', icon: 'book', min: 'view', availability: 'live',
      summary: { book: 'b1', line: 'The Salt Road · 4%', progress: 0.04 } }], who: { [ME]: 'desk browser', 'phone-1': 'phone' } },
    'desc/reader': { id: 'reader', name: 'Reader', min: 'view', state: 'ok', shelf: { folder: '', folders: ['Essays', 'Series with a long folder name'], books: BOOKS },
      actions: [{ id: 'open', kind: 'r', level: 'view', label: 'Open' }, { id: 'place', kind: 'r', level: 'view', label: 'Place' },
        { id: 'bookmark', kind: 'r', level: 'control', label: 'Bookmark' }, { id: 'reset', kind: 'i', level: 'control', label: 'Reset progress' }] },
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
};
