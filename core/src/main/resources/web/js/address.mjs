// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-1, package P7 (INTERFACES.md W-04; CONTRACT.md §5): an address per window and place,
// `/w/<window>/<target>[@<anchor>]`. Path parts are percent-encoded; `:` stays readable. The PC answers the page for
// any `/w/` path, so a reload or a link lands here. Pure.

const KEEP = /[A-Za-z0-9\-._~:!$&'()*+,;=]/;

/** One path part, percent-encoded: everything outside RFC 3986's segment set, and `/`, `@`, `%` and `?`. */
export function enc(s) {
  let out = '';
  for (const ch of String(s)) {
    if (KEEP.test(ch)) out += ch;
    else for (const b of new TextEncoder().encode(ch)) out += '%' + b.toString(16).toUpperCase().padStart(2, '0');
  }
  return out;
}

function dec(s) {
  try {
    return decodeURIComponent(s);
  } catch {
    return null;
  }
}

/** The path of {window, target?, anchor?}; home is `/`. */
export function format(a) {
  if (!a || !a.window || a.window === 'main') return '/';
  let p = '/w/' + enc(a.window);
  if (a.target) p += '/' + enc(a.target) + (a.anchor ? '@' + enc(a.anchor.replace(/^@/, '')) : '');
  return p;
}

/** A location's path as {window, target, anchor}: `/` is Main; null for a path that is not an address. */
export function parse(path) {
  if (path === '/' || path === '' || path === '/index.html') return { window: 'main', target: null, anchor: null };
  const m = /^\/w\/([^/]+)(?:\/(.*))?$/.exec(path);
  if (!m) return null;
  const window = dec(m[1]);
  if (!window) return null;
  let target = null;
  let anchor = null;
  if (m[2] !== undefined && m[2] !== '') {
    // the anchor follows the first raw `@` (an `@` inside a part is always encoded)
    const at = m[2].indexOf('@');
    target = dec(at < 0 ? m[2] : m[2].slice(0, at));
    if (target === null) return null;
    if (at >= 0) {
      const a = dec(m[2].slice(at + 1));
      if (a === null) return null;
      anchor = '@' + a;
    }
  }
  return { window, target, anchor };
}
