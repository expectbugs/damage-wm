// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-1, package P7 (the spec's §2 P7 "Reader"; decisions A.2, A.10; CONTRACT.md §2.6): Reader's web page
// without the DOM — the progress of a book, the part a place falls in, a place's quote from a part's document. Pure.

import { EXTRACTOR, QUOTE, format, offsetOf } from './anchor.mjs';

/** A book's progress 0..1 from the record's place and the card's text length, else the card's own fraction. */
export function progress(book, placeValue) {
  const off = offsetOf(placeValue);
  if (book && book.length > 0) return Math.min(1, Math.max(0, (off ?? 0) / book.length));
  return book && typeof book.fraction === 'number' ? book.fraction : null;
}

export function percent(f) {
  return f === null || f === undefined ? '' : `${Math.round(f * 100)}%`;
}

/** The index of the chapter a character offset falls in: the last whose anchor is at or before it (0 when none). */
export function partFor(chapters, offset) {
  let n = 0;
  (chapters || []).forEach((c, i) => {
    const o = offsetOf(c.anchor);
    if (o !== null && o <= offset) n = i;
  });
  return n;
}

/** A block's text when the document carries it (its runs); null for an image or a rule. */
export function blockText(b) {
  if (b.kind === 'image' || b.kind === 'rule') return null;
  return (b.spans || []).map((s) => s.text).join('');
}

/**
 * The place at `offset` in the part `doc`: its anchor text with the quote the page can vouch for — the book's text
 * from the offset (blocks are joined by a blank line, as the book's text is), up to 32 characters, stopping where a
 * block's text is not carried (an image) or the part ends. A shorter quote is still the text at the offset.
 */
export function placeAt(doc, offset) {
  let quote = null;
  let prevEnd = 0;
  for (const b of doc.blocks || []) {
    const t = blockText(b);
    const whole = t !== null && t.length === b.charEnd - b.charStart;
    if (quote === null) {
      if (offset < b.charStart) break; // between blocks: no quote to vouch for
      if (offset >= b.charEnd && !(b.charStart === b.charEnd && offset === b.charStart)) continue;
      if (!whole) break;
      quote = t.slice(offset - b.charStart);
    } else {
      // the next block of the book's text only when it follows after exactly one blank line
      if (!whole || b.charStart !== prevEnd + 2) break;
      quote += '\n\n' + t;
    }
    prevEnd = b.charEnd;
    if (quote.length >= QUOTE) break;
  }
  return format({ offset, ver: EXTRACTOR, quote: (quote ?? '').slice(0, QUOTE) });
}
