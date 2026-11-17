// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-2, package Q4w: Torrents' descriptions and scopes in the shapes the page assumes from the spec (§3.1, §2.2;
// the assumptions are listed in the stage's report for the Kotlin lane), for the node tests and the look harness.
// Every name, hash, path and tracker row here is made up; addresses are documentation ones (RFC 2606).

export const T_NOW = Date.UTC(2026, 10, 15, 14, 2);
const DAY = 86400e3;

const WORDS = ['Salt', 'Lantern', 'Copper', 'Ferry', 'River', 'Harbour', 'Quiet', 'Northern', 'Glass', 'Orchard', 'Signal', 'Tide'];
const NOUNS = ['Road', 'Weather', 'Hill', 'Night', 'Map', 'Lights', 'Field', 'Station', 'House', 'Season', 'Line', 'Garden'];

/** The glasses' state word and band for a raw state (TorrentsWindow.stateWord; error = alarm, stalled = attention). */
function word(raw, dl) {
  if (raw === 'error' || raw === 'missingFiles') return ['error', 'alarm'];
  if (raw.startsWith('checking')) return ['checking', 'normal'];
  if (raw === 'stoppedDL' || raw === 'stoppedUP') return ['stopped', 'normal'];
  if (raw === 'metaDL') return ['metadata', 'normal'];
  if (raw === 'queuedDL' || raw === 'queuedUP') return ['queued', 'normal'];
  if (raw === 'stalledDL') return ['stalled', 'attention'];
  if (raw === 'downloading' || raw === 'forcedDL') return [`${(dl / 1048576).toFixed(1)} MB/s`, 'normal'];
  return ['seeding', 'normal'];
}

const RAWS = ['downloading', 'uploading', 'stalledUP', 'stalledDL', 'stoppedUP', 'uploading', 'error', 'downloading', 'stalledUP', 'checkingUP',
  'stoppedDL', 'queuedDL', 'uploading', 'metaDL', 'missingFiles', 'forcedUP', 'stalledUP', 'downloading', 'uploading', 'stalledUP'];

/** Transfer `i` of the made-up table: mixed states, a long name now and then, some seeding under a week. */
export function transfer(i, over = {}) {
  const raw = over.raw || RAWS[i % RAWS.length];
  const done = !/DL$|^downloading$|^metaDL$/.test(raw) && raw !== 'error';
  const progress = over.progress ?? (done ? 1 : ((i * 37) % 97) / 100);
  const k = 1 + 0.6 * Math.sin(i * 1.7 + (over.tick || 0) * 0.9); // a later snapshot's speeds differ
  const dl = /^downloading$|^forcedDL$/.test(raw) ? Math.round((300000 + ((i * 731) % 9) * 410000) * k) : 0;
  const up = /^uploading$|^forcedUP$/.test(raw) ? Math.round((20000 + ((i * 113) % 7) * 90000) * k) : 0;
  const size = (1 + ((i * 53) % 40)) * 230e6;
  const name = i % 9 === 4
    ? `${WORDS[i % 12]}.${NOUNS[(i * 5) % 12]}.and.the.${WORDS[(i + 3) % 12]}.${NOUNS[(i + 7) % 12]}.Collected.Edition.2026.2160p.WEB-DL.DDP5.1.Atmos.HEVC`
    : `${WORDS[i % 12]} ${NOUNS[(i * 5) % 12]}${i % 3 ? ` (${2010 + (i % 16)})` : ''} ${['1080p', '720p', 'FLAC', 'EPUB'][i % 4]}`;
  const seeding = done ? ((i * 29) % 14) * 0.9 * DAY : 0;
  const [w, band] = word(raw, dl);
  return { id: over.id || `c0ffee${String(i).padStart(2, '0')}${'ab'.repeat(16)}`.slice(0, 40), kind: 'transfer', title: over.title || name, fields: {
    state: { word: w, band, raw }, progress, size, downloaded: Math.round(size * progress), uploaded: Math.round(size * ((i % 5) * 0.6)),
    dlSpeed: dl, upSpeed: up, eta: dl ? Math.round((size * (1 - progress)) / dl) * 1000 : null, ratio: done ? (i % 5) * 0.61 : 0.02,
    seeds: (i * 7) % 40, peers: (i * 3) % 25, added: T_NOW - (i + 1) * 0.7 * DAY, completed: done ? T_NOW - seeding : null, seedingTime: seeding,
    savePath: '/home/someone/Downloads', contentPath: `/home/someone/Downloads/${name}`, category: '', tags: '', tracker: 'https://tracker.example.org/announce',
    ...(over.fields || {}) } };
}

export const ACTIONS = [
  { id: 'start', label: 'Start', kind: 'r', level: 'control', into: 'program', targets: 'many' },
  { id: 'stop', label: 'Stop', kind: 'r', level: 'control', into: 'program', targets: 'many' },
  { id: 'recheck', label: 'Recheck', kind: 'r', level: 'control', into: 'program', targets: 'many' },
  { id: 'delete', label: 'Delete', kind: 'i', level: 'control', into: 'program', targets: 'many', confirm: {} },
  { id: 'deleteFiles', label: 'Delete with files', kind: 'i', level: 'full', into: 'program', targets: 'many', confirm: { double: true } },
  { id: 'add', label: 'Add', kind: 'o', level: 'full', into: 'program', targets: 'one', confirm: {} },
  { id: 'addStopped', label: 'Add stopped', kind: 'o', level: 'full', into: 'program', targets: 'one', confirm: {} },
  { id: 'openOnPc', label: 'Open on the PC', kind: 'r', level: 'full', into: 'program', targets: 'one' },
  { id: 'openInFiles', label: 'Open in Files', kind: 'v', level: 'view', into: null, targets: 'one' },
  { id: 'recent', label: 'Keep the search', kind: 'register', level: 'full', into: 'record', targets: 'one' },
  { id: 'set', label: 'Set a row', kind: 'register', level: 'control', into: 'record', targets: 'one' },
];

export const SETTINGS = [
  { id: 'notify.done', label: 'Notify · done', scope: 'shared', type: 'bool', value: true, level: 'control' },
  { id: 'notify.errors', label: 'Notify · errors', scope: 'shared', type: 'bool', value: true, level: 'control' },
  { id: 'poll', label: 'Poll', scope: 'device', type: 'choice', options: [{ id: 1000, label: '1 s' }, { id: 2000, label: '2 s' }, { id: 5000, label: '5 s' }], value: 2000, level: 'view' },
  { id: 'glasses.size', label: 'Size (glasses)', scope: 'glasses', type: 'choice', level: 'control', value: 'global',
    options: [{ id: 'global', label: 'global' }, { id: 288, label: '288' }, { id: 352, label: '352' }, { id: 416, label: '416' }, { id: 480, label: '480' }] },
];

/** `desc/torrents` (spec §3.1) with `n` transfers. */
export function torrentsDesc({ n = 40, version = 7, epoch = 1763200000000, transfers, samples = 150, tick = 0 } = {}) {
  const ts = transfers || Array.from({ length: n }, (_, i) => transfer(i, { tick }));
  const speeds = Array.from({ length: samples }, (_, i) => ({ at: T_NOW - (samples - i) * 2000,
    dl: Math.round(2.4e6 + 1.6e6 * Math.sin(i / 9) + ((i * 7919) % 13) * 6e4), up: Math.round(4.2e5 + 2e5 * Math.cos(i / 13)) }));
  const dlN = ts.filter((t) => /DL$|^downloading$/.test(t.fields.state.raw) && t.fields.state.raw !== 'stoppedDL').length;
  return { id: 'torrents', name: 'Torrents', icon: 'torrents', min: 'view', title: 'transfers', availability: 'live',
    health: { qbt: { state: 'ok' }, tracker: { state: 'ok' } },
    summary: { line: ts.length ? `${dlN} downloading · 3.1 MB/s` : 'idle', detail: ts.length ? `${ts.length - dlN} seeding · 2 stopped · 2 errors` : '', counts: {} },
    epoch, version, atMs: T_NOW,
    session: { dlSpeed: ts.length ? 3.1 * 1048576 : 0, upSpeed: ts.length ? 420 * 1024 : 0, dlTotal: 38.2e9, upTotal: 12.9e9, ratio: '2.13', free: 812e9, peers: 61, connection: 'connected', version: 'v5.1.4' },
    speeds, transfers: ts, actions: ACTIONS, settings: SETTINGS };
}

export const CATEGORIES = [[8, 'Movies', 'HD'], [12, 'Movies', '4K'], [26, 'TV', 'Episodes HD'], [27, 'TV', 'Boxsets'],
  [17, 'Games', 'PC'], [33, 'Apps', 'Linux'], [45, 'Books', 'EBooks'], [31, 'Music', 'Audio'], [38, 'Education', 'Courses']].map(([id, group, name]) => ({ id, group, name }));

/** A tracker row (`{id: fid, kind, title, fields}`; sizes in bytes, the site's own UTC timestamp text). */
export function tlRow(i, q = '') {
  const d = new Date(T_NOW - (i * 3 + 1) * 3600e3);
  const stamp = d.toISOString().slice(0, 19).replace('T', ' ');
  return { id: String(240000 + i), kind: 'tracker', title: `${q ? `${q[0].toUpperCase()}${q.slice(1)} ` : ''}${WORDS[i % 12]}.${NOUNS[(i * 7) % 12]}.S0${1 + (i % 4)}E${String(1 + (i % 9)).padStart(2, '0')}.1080p.WEB.H264${i % 6 === 2 ? '.REPACK.PROPER.Extended.Directors.Cut' : ''}`,
    fields: { size: (1 + ((i * 17) % 30)) * 310e6, seeders: (i * 37) % 900, leechers: (i * 11) % 60, snatched: (i * 53) % 4000, added: stamp,
      category: CATEGORIES[i % CATEGORIES.length].id, freeleech: i % 5 === 0, tags: i % 4 ? [] : ['remux'] } };
}

/** A page of a listing (spec §2.2): `{count, version, from, size, rows, more}`; `total` rows in all, 35 a page. */
export function tlPage(n, total, q = '', version = 1) {
  const from = n * 35;
  const rows = Array.from({ length: Math.max(0, Math.min(35, total - from)) }, (_, i) => tlRow(from + i, q));
  return { count: null, version, from, size: rows.length, rows, more: from + rows.length < total };
}

export const TL_PAGE = { fid: '240001', name: 'Lantern.Weather.S01E02.1080p.WEB.H264', category: 'TV · Episodes HD', size: '1.42 GB', seeders: 312, leechers: 14,
  snatched: 2210, added: '2026-11-15 09:12:44', uploader: 'a-made-up-uploader', tags: ['web', '1080p'],
  description: 'Episode two of a made-up series.\nThe second line keeps its break.',
  nfo: `  _                 _                    \n | |   __ _ _ __ | |_ ___ _ __ _ __    \n | |__/ _\` | '_ \\| __/ _ \\ '__| '_ \\  \n |____\\__,_|_| |_|\\__\\___|_|  |_| |_|  \n\n Video ......: 1920x1080 H264 at 6 200 kb/s (a wide line to show the NFO scrolls sideways, never cut)\n Audio ......: AAC 2.0\n`,
  files: [{ name: 'Lantern.Weather.S01E02.1080p.WEB.H264.mkv', size: '1.41 GB' }, { name: 'Lantern.Weather.S01E02.nfo', size: '2 KB' }],
  url: 'https://tracker.example.org/torrent/240001' };

export const ACCOUNT = { user: 'a-made-up-user', uploaded: '1.23 TB', downloaded: '456.7 GB', ratio: '2.71', points: '12 345', klass: 'Member' };

export const DETAIL = (hash) => ({ hash, files: [{ name: 'Salt Road (2026) 1080p/Salt.Road.2026.1080p.mkv', size: 4.2e9, progress: 0.47, priority: 1 },
  { name: 'Salt Road (2026) 1080p/Subs/English.srt', size: 88e3, progress: 1, priority: 1 }], comment: '', trackers: ['https://tracker.example.org/announce'] });

/** What the stand-in answers for a Torrents scope (the look harness), or undefined. */
export function torrentsScope(scope) {
  if (scope === 'torrents/categories') return { categories: CATEGORIES };
  if (scope === 'torrents/account') return ACCOUNT;
  if (scope.startsWith('torrents/t:')) return DETAIL(scope.slice(11));
  if (scope.startsWith('torrents/tl:')) return { ...TL_PAGE, fid: scope.slice(12) };
  const m = /^torrents\/(listing|search):([^/]*)\/sort:[a-z]+\/page:(\d+)$/.exec(scope);
  if (!m) return undefined;
  const q = m[1] === 'search' ? decodeURIComponent(m[2]) : '';
  return tlPage(Number(m[3]), m[1] === 'listing' ? 200 : /^zz/.test(q) ? 0 : 12, q);
}

/** The look harness's record scope `torrents`: the search recents and one row set elsewhere. */
export const torrentsRecord = () => ({ t: 'snap', scope: 'torrents', epoch: 3, seq: 40, items: {
  'rec.torrents.recents': { v: ['lantern weather', 'salt road', 'copper hill 2160p'], by: 'phone-1', at: T_NOW - 3600e3 },
  'rec.torrents.notify.errors': { v: false, by: 'phone-1', at: T_NOW - 86400e3 } } });

/** Several snapshots in a row, so the rows' speed lines have points (the page draws what it received). */
export const torrentsSeries = (n = 12) => Array.from({ length: n }, (_, i) => torrentsDesc({ version: 7 + i, tick: i }));
