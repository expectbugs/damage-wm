// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-1, package P7: a place in a book as the PC record holds it (CONTRACT.md §5; the Kotlin twin is
// core/…/windows/reader/ReaderAnchor.kt). Written `@c<offset>.<ver>~<quote>`: a character offset into the text the
// extractor version made, and up to 32 characters of that text from the offset. Pure: no DOM, no network.

export const EXTRACTOR = 1;
export const QUOTE = 32;

const FORM = /^@c(\d{1,10})\.(\d{1,4})(?:~([\s\S]*))?$/;

/** The anchor's text form. */
export function format(a) {
  return `@c${a.offset}.${a.ver ?? EXTRACTOR}~${a.quote ?? ''}`;
}

/** `s` as {offset, ver, quote}, or null when it is not a place (never a guess). */
export function parse(s) {
  if (typeof s !== 'string') return null;
  const m = FORM.exec(s);
  if (!m) return null;
  const offset = Number(m[1]);
  const ver = Number(m[2]);
  if (!Number.isSafeInteger(offset) || offset > 2147483647 || ver < 1) return null;
  return { offset, ver, quote: m[3] ?? '' };
}

/** The offset of a place value as the record holds it (a JSON string), or null. */
export function offsetOf(v) {
  const a = parse(v);
  return a ? a.offset : null;
}
