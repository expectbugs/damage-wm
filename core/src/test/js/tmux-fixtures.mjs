// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-3, piece B17 (first built as package JT part 1): Tmux in the shapes the lean spec gives the PC's scopes (B9:
// `desc/tmux`, a session's canvas, its history; B11: `live` frames with colour runs, and the control-mode stream's
// `init` and `data`). Every host, session, command and line of output here is made up; nothing was read from a tmux
// server. Frames: a shell prompt, a full-screen editor in colour, a table of wide characters and box drawing with ruler
// rows for measuring the columns in a picture, a program waiting for input, and a long build log as history.

export const T_NOW = Date.UTC(2026, 10, 15, 14, 2);

/** A run: text and its colours (an index 0–255 or `#rrggbb`) and flags (1 bold, 2 dim, 4 underline, 8 reverse). */
const R = (t, fg = null, bg = null, fl = 0) => ({ t, fg, bg, fl });
/** A row from parts: a string is plain, an array is [text, fg, bg, fl]. */
const row = (...parts) => parts.map((p) => (typeof p === 'string' ? R(p) : R(...p)));

const PROMPT = (cwd = '~/src/kettle') => [['me@workstation', 2, null, 1], ':', [cwd, 4, null, 1], '$ '];

/** A shell: a listing in colour, a short git status, a check's output, the prompt with the cursor after it. */
export function shellFrame() {
  const lines = [
    row(...PROMPT(), 'ls --color'),
    row(['build', 12, null, 1], '  ', ['docs', 12, null, 1], '  ', ['src', 12, null, 1], '  ', ['tools', 12, null, 1], '  README.md  ', ['run.sh', 10, null, 1], '  kettle.toml'),
    row(...PROMPT(), 'git status --short'),
    row([' M', 1], ' src/brew.js'),
    row([' M', 1], ' src/steep.js'),
    row(['??', 1], ' notes/teapots-with-a-much-longer-name-than-the-others.md'),
    row(...PROMPT(), 'make check'),
    row(['[ok]  ', 2, null, 1], 'lint       0 findings'),
    row(['[ok]  ', 2, null, 1], 'tests      214 passed, 0 failed'),
    row(['[warn]', 3, null, 1], ' docs       2 lines longer than 100 columns'),
    row(...PROMPT()),
  ];
  const x = 'me@workstation:~/src/kettle$ '.length;
  return { lines, cols: 80, rows: 24, cursor: { x, y: lines.length - 1, visible: true }, alternate: false, capturedAt: T_NOW - 4000 };
}

/** A full-screen editor: line numbers, keywords, strings in true colour, numbers from the 256-colour cube, a comment
 *  dimmed, the empty-line marks, a reverse-video status line and the mode line. */
export function editorFrame() {
  const n = (i) => [`${String(i).padStart(3)} `, 130];
  const kw = (t) => [t, 5, null, 1];
  const strg = (t) => [t, '#2aa198'];
  const num = (t) => [t, 166];
  const code = [
    row(n(1), ['// a made-up file in a made-up editor', 244, null, 2]),
    row(n(2), kw('function'), ' brew(kettle, minutes) {'),
    row(n(3), '  ', kw('const'), ' water = kettle.fill(', num('1.2'), ');'),
    row(n(4), '  ', kw('if'), ' (minutes > ', num('4'), ') ', kw('return'), ' ', strg('"too strong"'), ';'),
    row(n(5), '  ', kw('return'), ' water.heat(', num('96'), ').steep(minutes);'),
    row(n(6), '}'),
    row(n(7)),
    row(n(8), kw('export'), ' { brew };'),
  ];
  const lines = [...code];
  while (lines.length < 22) lines.push(row(['~', 4, null, 1]));
  lines.push(row(['"src/brew.js" 8L, 196B', null, null, 8], [' '.repeat(38), null, null, 8], ['4,26', null, null, 8], [' '.repeat(10), null, null, 8], ['All', null, null, 8], ['  ', null, null, 8]));
  lines.push(row(['-- INSERT --', null, null, 1]));
  return { lines, cols: 80, rows: 24, cursor: { x: 30, y: 3, visible: true }, alternate: true, capturedAt: T_NOW - 2000 };
}

/** Ruler rows: a red `|` at columns 0, 10, …, 50; between them nine columns of each kind of content. A picture of this
 *  frame is measured: every row's bars must sit at the same x. */
export const RULER_KINDS = [
  ['ascii', 'abcdefghi'],
  ['wide', '日本語abc'],
  ['box', '─────────'],
  ['combining', 'é'.repeat(9)],
  ['emoji', '\u{1F600}\u{1F600}\u{1F600}\u{1F600}a'],
  ['mixed', 'é─日a b┼x'],
];
const BAR = 9; // bright red
export function rulerRow(fill) {
  const parts = [];
  for (let i = 0; i < 5; i++) parts.push(['|', BAR], fill);
  parts.push(['|', BAR]);
  return row(...parts);
}

/** A table of wide characters and box drawing, a combining accent, then the ruler rows. */
export function tablesFrame() {
  const g = (t) => [t, 6];
  const lines = [
    row(g('┌──────────┬──────────────┬────────┐')),
    row(g('│'), ' name     ', g('│'), ' 名前         ', g('│'), ' count  ', g('│')),
    row(g('├──────────┼──────────────┼────────┤')),
    row(g('│'), ' kettle   ', g('│'), ' やかん       ', g('│'), ['     12', 2], ' ', g('│')),
    row(g('│'), ' teapot   ', g('│'), ' 急須         ', g('│'), ['      3', 2], ' ', g('│')),
    row(g('│'), ' café     ', g('│'), ' カフェ       ', g('│'), ['      7', 2], ' ', g('│')),
    row(g('└──────────┴──────────────┴────────┘')),
    row(),
    ...RULER_KINDS.map(([, fill]) => rulerRow(fill)),
    row(...PROMPT('~/src/kettle/docs')),
  ];
  return { lines, cols: 60, rows: 16, cursor: { x: 34, y: lines.length - 1, visible: true }, alternate: false, capturedAt: T_NOW - 6000 };
}

/** A program asking before it runs a command (the glasses' approval loop): a box in true colour, the choice marked. */
export function waitingFrame() {
  const o = (t) => [t, '#d77757'];
  const w = 66;
  const line = (t, fg = null, fl = 0) => row(o('│'), [` ${t}`.padEnd(w - 2), fg, null, fl], o('│'));
  const lines = [
    row(['● ', 2], ['Shell', null, null, 1], '(rm -rf build/tmp && make package)'),
    row('  ⎿  Waiting…'),
    row(),
    row(o(`╭${'─'.repeat(w - 2)}╮`)),
    line('Shell command', null, 1),
    line(''),
    line('  rm -rf build/tmp && make package'),
    line('  Clear the scratch folder and build the package', 244),
    line(''),
    line('Do you want to proceed?'),
    line('❯ 1. Yes', 12, 1),
    line('  2. Yes, and do not ask again for this command'),
    line('  3. No, and say what to do instead (esc)'),
    row(o(`╰${'─'.repeat(w - 2)}╯`)),
  ];
  return { lines, cols: 80, rows: 24, cursor: { x: 0, y: 0, visible: false }, alternate: false, capturedAt: T_NOW - 1000 };
}

/** A long build log: `n` lines, coloured by level, some longer than the pane. */
export function historyLines(n = 1000) {
  const out = [];
  for (let i = 0; i < n; i++) {
    const t = `[${String(13 + Math.floor(i / 600)).padStart(2, '0')}:${String(Math.floor(i / 10) % 60).padStart(2, '0')}:${String((i * 7) % 60).padStart(2, '0')}]`;
    if (i % 97 === 0) out.push(row([t, 244], ' ', ['ERROR', 1, null, 1], ` step ${i}: the made-up check found a teapot without a lid (src/pots/${i}.toml)`));
    else if (i % 13 === 0) out.push(row([t, 244], ' ', ['WARN ', 3], ` step ${i}: steeping took longer than its budget; the log line is long so that it wraps past the pane's eighty columns`));
    else out.push(row([t, 244], ' ', ['INFO ', 2], ` step ${i}: compiled module ${i % 40} of 40`));
  }
  out.push(row([`[14:01:58]`, 244], ' ', ['DONE ', 2, null, 1], ' BUILD OK in 4m 12s'));
  return out;
}

const s = (host, name, extra = {}) => ({ host, name, windows: 1, attached: false, activity: T_NOW - 600e3, waiting: false, lastLine: '', muted: false, ...extra });

export const SESSIONS = [
  s('workstation', 'shell', { attached: true, activity: T_NOW - 20e3, lastLine: 'me@workstation:~/src/kettle$' }),
  s('workstation', 'editor', { windows: 3, activity: T_NOW - 90e3, lastLine: '-- INSERT --' }),
  s('workstation', 'build', { windows: 2, activity: T_NOW - 40e3, waiting: true, lastLine: 'Do you want to proceed?' }),
  s('workstation', 'tables', { activity: T_NOW - 3600e3, lastLine: 'me@workstation:~/src/kettle/docs$' }),
  s('workstation', 'notes', { activity: T_NOW - 3 * 3600e3, lastLine: 'saved notes/teapots.md' }),
  s('buildbox', 'logs', { activity: T_NOW - 2 * 3600e3, lastLine: '[14:01:58] DONE  BUILD OK in 4m 12s' }),
];

const A = (id, label, kind) => ({ id, label, kind, level: 'full' });
export const ACTIONS = [A('key', 'Key', 'live'), A('snippet', 'Snippet', 'live'), A('line', 'Run it', 'live'), A('raw', 'Keyboard', 'live'), A('new', 'New session', 'live'),
  A('rename', 'Rename', 'live'), A('end', 'End session', 'live'), A('select', 'Select window', 'live'), A('mute', 'Mute alerts', 'register'), A('set', 'Set', 'register')];

/** `TmuxConfig`'s defaults (the glasses' fourteen quick keys and G2CC's snippets). */
export const QUICK = ['Enter', 'y', 'n', '1', '2', '3', 'Escape', 'C-c', 'Up', 'Down', 'Left', 'Right', 'Tab', 'q'];
export const SNIPPETS = ['/clear', '/compact', '/resume', '/cost', '/model', '/status', '/config', '/agents', '/review', '/help', '/exit'];

export function tmuxDesc({ sessions = SESSIONS, hosts, actions = ACTIONS } = {}) {
  return {
    summary: { line: `${sessions.length} sessions · ${sessions.filter((x) => x.waiting).length} waiting` },
    asOf: T_NOW,
    sessions,
    hosts: hosts || [{ name: 'workstation', health: { state: 'ok', lastOkMs: T_NOW - 1000 } }, { name: 'buildbox', health: { state: 'quiet', lastOkMs: T_NOW - 42e3 } }],
    keys: { quick: QUICK, snippets: SNIPPETS },
    actions,
    settings: [
      { id: 'glasses.context', label: 'Context rows', scope: 'glasses', type: 'bool', value: true, level: 'full' },
      { id: 'alerts', label: 'Alerts', scope: 'shared', type: 'bool', value: true, level: 'full' },
      { id: 'glasses.size', label: 'Size', scope: 'glasses', type: 'choice', options: [{ id: 'small', label: 'Small' }, { id: 'medium', label: 'Medium' }, { id: 'large', label: 'Large' }], value: 'medium', level: 'full' },
    ],
  };
}

export const tmuxRecord = () => ({ t: 'snap', scope: 'tmux', epoch: 3, seq: 40, items: {
  'rec.tmux.alerts': { v: true, by: 'phone-1', at: T_NOW - 86400e3 },
  'rec.tmux.mute.workstation/notes': { v: true, by: 'phone-1', at: T_NOW - 7200e3 },
} });

const FRAMES = { shell: shellFrame, editor: editorFrame, tables: tablesFrame, build: waitingFrame, notes: shellFrame, logs: shellFrame };

export function canvasOf(host, name) {
  const f = (FRAMES[name] || shellFrame)();
  const windows = name === 'editor' ? [{ idx: 0, name: 'vim', active: true, bell: false }, { idx: 1, name: 'server', active: false, bell: true }, { idx: 2, name: 'tests', active: false, bell: false }]
    : [{ idx: 0, name: 'bash', active: true, bell: false }];
  return { cols: f.cols, rows: f.rows, alternate: f.alternate, windows };
}

export const frameOf = (name) => (FRAMES[name] || shellFrame)();

/** The stand-in's answer for a Tmux scope, or null. */
export function tmuxScope(scope) {
  if (scope === 'desc/tmux') return tmuxDesc();
  let m = /^tmux\/session:(.+):([^:]+)$/.exec(scope);
  if (m) return canvasOf(m[1], m[2]);
  m = /^tmux\/history:(.+):([^:]+)$/.exec(scope);
  if (m) return { capturedAt: T_NOW - 3000, lines: historyLines() };
  return null;
}

// ---- the control-mode stream (B10): what `capture-pane -p -e` gives, and output as it comes ----

const SGR_FG = (c) => (typeof c === 'string' ? `38;2;${[1, 3, 5].map((i) => parseInt(c.slice(i, i + 2), 16)).join(';')}` : c < 8 ? `${30 + c}` : c < 16 ? `${82 + c}` : `38;5;${c}`);
const SGR_BG = (c) => (typeof c === 'string' ? `48;2;${[1, 3, 5].map((i) => parseInt(c.slice(i, i + 2), 16)).join(';')}` : c < 8 ? `${40 + c}` : c < 16 ? `${92 + c}` : `48;5;${c}`);
/** Runs as the text with colour escapes a terminal reads (what `capture-pane -e` writes for a pane). */
export function sgrOf(lines) {
  return lines.map((runs) => runs.map((r) => {
    const codes = ['0'];
    if (r.fl & 1) codes.push('1');
    if (r.fl & 2) codes.push('2');
    if (r.fl & 4) codes.push('4');
    if (r.fl & 8) codes.push('7');
    if (r.fg !== null && r.fg !== undefined) codes.push(SGR_FG(r.fg));
    if (r.bg !== null && r.bg !== undefined) codes.push(SGR_BG(r.bg));
    return `\x1b[${codes.join(';')}m${r.t}`;
  }).join('') + '\x1b[0m').join('\n');
}
const b64 = (text) => Buffer.from(text, 'utf8').toString('base64');

/** The stream's `init` for a session (ASSUMED shape, `terminal.mjs` `initWrites`): its pane as captured, its size,
 *  its cursor. */
export function streamInit(name) {
  const f = frameOf(name);
  return { cols: f.cols, rows: f.rows, cursor: { x: f.cursor.x, y: f.cursor.y }, alternate: f.alternate, data: b64(sgrOf(f.lines)) };
}

/** Output a command makes after the init (the stand-in's `/__push?name=output`). */
export const OUTPUT = 'make package\r\n\x1b[32m[ok]\x1b[0m  packaged kettle-0.4.2.tar.gz (212 KB)\r\n\x1b[1;32mme@workstation\x1b[0m:\x1b[1;34m~/src/kettle\x1b[0m$ ';
export const streamData = (text) => b64(text);

/** The stand-in's `live` messages for a source: a fresh `init` (frames: the picture; the stream: the pane as
 *  captured), then a scene's burst of frames (`burst`: the page draws only the newest). */
export function tmuxLive(scope, scene = {}) {
  let m = /^live\/tmux\/frame\/(.+)\/([^/]+)$/.exec(scope);
  if (m) {
    const f = frameOf(m[2]);
    const out = [{ t: 'live', src: scope, init: f }];
    if (scene.burst) for (let i = 0; i < scene.burst; i++) out.push({ t: 'live', src: scope, frame: { ...f, capturedAt: T_NOW + i } });
    return out;
  }
  m = /^live\/tmux\/stream\/(.+)\/([^/]+)$/.exec(scope);
  if (m) return [{ t: 'live', src: scope, init: streamInit(m[2]) }];
  return null;
}
