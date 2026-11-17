// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-2, package Q5w: Files' descriptions and scopes in the shapes the page assumes from the spec (§4.1, §2.2;
// the assumptions are listed in the stage's report for the Kotlin lane), for the node tests and the look harness.
// Every name and path here is made up.

export const F_NOW = Date.UTC(2026, 10, 15, 14, 2);
const DAY = 86400e3;
export const HOME = '/home/ada';
export const DL = `${HOME}/Downloads`;
export const BOOKS = `${HOME}/books`;
export const EMPTY = `${HOME}/empty`;
/** S1-2 R2 item 18: the PC's own trash store, named in `locations` with kind `trash` while the trash holds anything. */
export const STORE = `${HOME}/.damage/trash`;

const A = (id, label, kind, extra = {}) => ({ id, label, kind, level: 'full', into: kind === 'v' ? null : ['copy', 'cut', 'sort', 'hidden'].includes(id) ? 'record' : 'program', ...extra });
/** The actions as the spec's §4.1 table lists them; every one at level full (Files' minimum). */
export const ACTIONS = [A('open', 'Open', 'v'), A('openInReader', 'Open in Reader', 'v'), A('download', 'Download', 'v'), A('openOnPc', 'Open on PC', 'r'),
  A('copy', 'Copy', 'r'), A('cut', 'Cut', 'r'), A('sort', 'Sort', 'r'), A('hidden', 'Hidden files', 'r'), A('paste', 'Paste', 'i', { job: true }),
  A('rename', 'Rename', 'i', { job: true }), A('mkdir', 'New folder', 'i', { job: true }), A('upload', 'Upload', 'i', { job: true }),
  A('trash', 'Move to Trash', 'r', { job: true, confirm: {} }), A('restore', 'Restore', 'r', { job: true }),
  A('purge', 'Delete forever', 'i', { job: true, confirm: { double: true } }), A('stats', 'Stats', 'job', { job: true })];
export const SETTINGS = [{ id: 'glasses.size', label: 'Size (glasses)', scope: 'glasses', type: 'choice', level: 'full', value: 'global',
  options: [{ id: 'global', label: 'as Global' }, { id: 288, label: '288' }, { id: 352, label: '352' }, { id: 416, label: '416' }, { id: 480, label: '480' }] }];
export const LOCATIONS = [
  { path: HOME, label: 'Home', kind: 'home', total: 3.6e12, free: 1.2e12 },
  { path: DL, label: 'Downloads', kind: 'downloads', total: 3.6e12, free: 1.2e12 },
  { path: BOOKS, label: 'Books', kind: 'books', total: 3.6e12, free: 1.2e12 },
  { path: '/mnt/archive', label: 'archive (a long label for a mounted disk of old things)', kind: 'mount', total: 8e12, free: 2.1e11 },
  { path: '/', label: 'Root', kind: 'root', total: 3.6e12, free: 1.2e12 },
];

export function filesDesc({ jobs = [], level = 'full', store = false } = {}) {
  return { id: 'files', name: 'Files', icon: 'folder', min: level, title: 'Files', availability: 'live', home: HOME, booksDir: BOOKS,
    locations: store ? [...LOCATIONS, { path: STORE, label: 'Trash', kind: 'trash' }] : LOCATIONS, jobs, actions: ACTIONS, settings: SETTINGS };
}

const WORDS = ['salt', 'lantern', 'copper', 'ferry', 'river', 'harbour', 'quiet', 'northern', 'glass', 'orchard', 'signal', 'tide'];
const EXTS = ['txt', 'jpg', 'pdf', 'mkv', 'epub', 'png', 'md', 'zip', 'flac', 'log'];

/** Entry `i` of a made-up folder: folders now and then, long names, pictures with thumbnails, a dotfile. */
export function entry(i, over = {}) {
  const dir = i % 11 === 0;
  const ext = EXTS[i % EXTS.length];
  const long = i % 7 === 3;
  const stem = long ? `${WORDS[i % 12]}-${WORDS[(i + 4) % 12]}-and-the-${WORDS[(i + 7) % 12]}-collected-notes-from-the-coast-road-volume-${i}-final-final-v2`
    : `${WORDS[i % 12]} ${WORDS[(i * 5) % 12]} ${i}`;
  const name = over.name || (i % 37 === 5 ? `.${WORDS[i % 12]}rc` : dir ? `${stem}` : `${stem}.${ext}`);
  const img = !dir && (ext === 'jpg' || ext === 'png');
  return { id: name, name, dir, size: dir ? 0 : (1 + ((i * 7919) % 5000)) * 12345, mtime: F_NOW - ((i * 3571) % 400) * DAY / 3,
    inline: img, thumb: img, ...over };
}

/** The made-up Downloads folder: `n` entries sorted as the PC sorts by name (folders first). */
export function folderRows(n = 200) {
  const rows = Array.from({ length: n }, (_, i) => entry(i));
  return rows.sort((a, b) => (b.dir ? 1 : 0) - (a.dir ? 1 : 0) || (a.name.toLowerCase() < b.name.toLowerCase() ? -1 : 1));
}

/** Page `n` of a listing of `rows` (spec §2.2: `{count, version, from, size, rows, more}`). */
export function folderPage(rows, n, size = 500, version = 1) {
  const from = n * size;
  return { count: rows.length, version, from, size, rows: rows.slice(from, from + size), more: from + size < rows.length };
}

export const TEXT = 'The road out of the salt flats ran straight for a day and a half.\n'.repeat(40);
export const TRASH = [
  { id: 't1', name: 'old notes.txt', origPath: `${DL}/old notes.txt`, dir: false, size: 4096, at: F_NOW - 3600e3 },
  { id: 't2', name: 'harbour photos', origPath: `${HOME}/Pictures/harbour photos`, dir: true, size: 3.2e8, at: F_NOW - 2 * DAY },
  { id: 't3', name: 'a-very-long-name-of-a-file-that-was-moved-to-the-trash-last-week-and-still-waits-there.mkv', origPath: `${DL}/a-very-long-name-of-a-file-that-was-moved-to-the-trash-last-week-and-still-waits-there.mkv`, dir: false, size: 1.4e9, at: F_NOW - 6 * DAY },
];

/** Files' scopes for the look harness: the listing of Downloads (two hundred entries), an empty folder, books, the
 *  trash, a text, a PDF's facts. Null for any other scope. */
export function filesScope(scope) {
  const page = /^files\/folder:(.*)\/page:(\d+)$/.exec(scope);
  if (page) {
    const dir = page[1];
    const rows = dir === DL ? folderRows(200) : dir === EMPTY ? [] : dir === BOOKS
      ? [entry(1, { name: 'The Salt Road.epub', reader: 'b1' }), entry(2, { name: 'Night Ferry.epub' })]
      : dir === HOME ? ['Downloads', 'books', 'empty', 'Pictures'].map((name, i) => entry(i * 11, { name, dir: true })) : null;
    return rows ? folderPage(rows, Number(page[2])) : null;
  }
  if (scope === 'files/trash') return { rows: TRASH };
  const text = /^files\/text:(.*)\/at:(\d+)$/.exec(scope);
  if (text) return { text: TEXT, more: false, totalBytes: TEXT.length, bytesRead: TEXT.length };
  if (/^files\/pdf:/.test(scope)) return { pages: 3, textChars: 2400 };
  return null;
}

/** Files' own record scope: the shared sort and hidden, a cut on the clipboard, the Size row. */
export const filesRecord = (over = {}) => ({ t: 'snap', scope: 'files', epoch: 3, seq: 40, colls: {}, items: {
  'rec.files.sort': { v: 'name', rev: 1, by: 'phone-1', at: F_NOW - DAY },
  'rec.files.hidden': { v: false, rev: 1, by: 'phone-1', at: F_NOW - DAY },
  'rec.files.clip': { v: { verb: 'cut', path: `${DL}/salt quiet 0.txt` }, rev: 3, by: 'phone-1', at: F_NOW - 600e3 },
  ...over } });

/** A running job in the slot (A.12): a copy, its fraction, its age and its last progress. */
export const runningCopy = (over = {}) => ({ id: 'j7', label: "Paste 'harbour photos'", state: 'running', fraction: 0.42, phase: 'copying',
  startedAt: F_NOW - 190e3, updatedAt: F_NOW - 2e3, answer: null, ...over });
