// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-1, package P7 (the spec's §2 P7 "Reader"; decisions A.2, A.10, E.6; CONTRACT.md §2.4, §2.6): Reader's web
// page without the DOM — the progress of a book, the part a place falls in, a place's quote from a part's document,
// the words of a loss, and the open book's place as one view holds it (`PlaceView`). Pure.

import { EXTRACTOR, QUOTE, format, offsetOf } from './anchor.mjs';
import { hhmm } from './proto.mjs';

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

/** A writer in words (the glasses' `who`): this browser, the PC, the phone, an earlier build, or a device's name. */
export function who(device, me, names) {
  if (device === me) return 'this browser';
  if (device === 'local') return 'the PC';
  if (device === 'phone') return 'the phone';
  if (device === 'migration') return 'an earlier build';
  if (!device) return 'another device';
  return (names && names[device]) || device;
}

/**
 * The words of one loss on Reader (the glasses' `ReaderGlasses.lost`, dec. E.6): `loss` as the answer carries it,
 * `action` the op's, `placeWords(value)` a place in words, `whoOf(device)` a writer in words.
 */
export function lossWords(loss, action, placeWords, whoOf) {
  const standing = loss.gone ? null : loss.standing;
  const whoAt = `${whoOf(loss.by)} (${hhmm(loss.at)})`;
  const item = loss.item || '';
  if (item.startsWith('place.')) {
    const mine = placeWords(loss.mine);
    if (action === 'reset') return `progress not reset: ${whoOf(loss.by)} read on (${hhmm(loss.at)})`;
    if (loss.gone) return `progress of this book was reset by ${whoAt}; this place, ${mine}, was not applied`;
    if (standing === null) return `this place, ${mine}, was not applied: the PC holds no place for this book now; reading on sends it again`;
    return `${whoOf(loss.by)} read on to ${placeWords(standing)} (${hhmm(loss.at)}); this place, ${mine}, was not applied`;
  }
  if (item.startsWith('bookmark.')) return `bookmark not applied: set by ${whoAt}`;
  return `Reader ${item.replace(/^glasses\./, '')} not applied: ${JSON.stringify(standing)}, set by ${whoAt}`;
}

/**
 * The open book's place as this page's view holds it (dec. A.2 on the web, dec. E.6): an entry takes the record's
 * place; another device's place for the open book never moves the page — it is offered (`moved`, the `Go there` bar)
 * until taken; this view's places expect what it took, so the first place after a move it did not take loses and is
 * said once, and the view then expects the standing place, so reading on never loses twice.
 */
export class PlaceView {
  constructor(me, book) {
    this.me = me;
    this.book = book;
    /** The record's place value this view took or confirmed (null = none there); `expect` follows it. */
    this.taken = null;
    this.expect = { value: null };
    /** The last place this view sent or took, as an offset (null = none). */
    this.offset = null;
    /** Another device's place, not taken: {value, by, at}. */
    this.moved = null;
    /** This view's place ops sent and not answered yet. */
    this.inFlight = 0;
  }

  /** An entry (opening the book, a page load, an address): takes the record's place item. Returns its offset or
   *  null (the book's start). */
  enter(item) {
    this.moved = null;
    if (!item) {
      this.taken = null;
      this.expect = { value: null };
      this.offset = null;
    } else if (item.gone) {
      this.taken = null;
      this.expect = { any: true };
      this.offset = null;
    } else {
      this.taken = item.v;
      this.expect = { value: item.v };
      this.offset = offsetOf(item.v);
    }
    return this.offset;
  }

  /** The record's place item for this book changed. True when the `Go there` offer changed. */
  fromRecord(item) {
    if (!item) return false;
    const v = item.gone ? null : item.v;
    if (item.by === this.me) {
      // fix round 1, ruling 2.2: the record holds this device's own place now; the moved one no longer stands
      const had = this.moved !== null;
      this.moved = null;
      return had;
    }
    // fix round 2, ruling 2.4: a reset elsewhere withdraws the offer too (the record holds no place to go to)
    if (v === this.taken || item.gone) {
      const had = this.moved !== null;
      this.moved = null;
      return had;
    }
    this.moved = { value: v, by: item.by, at: item.at };
    return true;
  }

  /**
   * A save point at `offset` with its anchor text: the op's {result, expect}, or null when the place has not moved.
   * `item` is the record's place item now: with none of this view's ops in flight, a place this device wrote means no
   * other device moved it since, so it is what the op expects (an earlier op whose answer never came — its connection
   * ended — is settled that way).
   */
  save(offset, anchorText, item) {
    if (offset === this.offset) return null;
    if (this.inFlight === 0 && item && item.by === this.me) this.expect = item.gone ? { any: true } : { value: item.v };
    const draft = { result: anchorText, expect: this.expect };
    this.inFlight++;
    this.offset = offset;
    // what this view expects next: its own place, once the PC holds it
    this.expect = { value: anchorText };
    return draft;
  }

  /** `Go there`: the moved place is taken. Returns its offset, or null when nothing is offered. */
  goThere() {
    if (!this.moved) return null;
    const v = this.moved.value;
    this.moved = null;
    this.taken = v;
    this.expect = { value: v };
    this.offset = offsetOf(v);
    return this.offset;
  }

  /** The answer to this view's place op that carried `sent`. Returns the loss when there was one (to be said). */
  answered(sent, answer) {
    this.inFlight = Math.max(0, this.inFlight - 1);
    if (!answer || answer.a !== 'lost') {
      if (answer && answer.a === 'seen') this.taken = sent;
      return null;
    }
    const loss = answer.losses.find((l) => (l.item || '').startsWith('place.')) || answer.losses[0];
    const standing = loss.gone ? null : loss.standing;
    // C§2.4: the view has been given the standing place; its next input expects it and applies
    this.expect = loss.gone ? { any: true } : { value: standing };
    this.taken = standing;
    // ruling 2.4: a save lost to a reset (or to no place) offers nothing; one lost to a place offers it
    this.moved = standing !== null ? { value: standing, by: loss.by, at: loss.at } : null;
    return loss;
  }
}
