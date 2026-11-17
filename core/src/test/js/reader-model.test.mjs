// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-1, package P7 (decisions A.7, A.2): Reader's web page without the DOM, under `node --test` (no packages, no
// network, no browser): a place's quote, a book's progress and the chapter a place falls in.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { placeAt, progress, percent, partFor, blockText } from '../../main/resources/web/js/reader-model.mjs';
import { parse } from '../../main/resources/web/js/anchor.mjs';

const doc = {
  charStart: 100, charEnd: 162,
  blocks: [
    { kind: 'heading', charStart: 100, charEnd: 109, spans: [{ text: 'Chapter 1', marks: [] }] },
    { kind: 'para', charStart: 111, charEnd: 142, spans: [{ text: 'It was a ', marks: [] }, { text: 'dark', marks: ['em'] }, { text: ' and stormy night.', marks: [] }] },
    { kind: 'image', charStart: 144, charEnd: 145, ref: 'reader.b1.0', spans: [] },
    { kind: 'para', charStart: 147, charEnd: 162, spans: [{ text: 'After the image', marks: [] }] },
  ],
};

test('a place quotes the book text the page holds, joined by a blank line, and stops at an image', () => {
  assert.equal(placeAt(doc, 100), '@c100.1~Chapter 1\n\nIt was a dark and sto');
  assert.equal(placeAt(doc, 120), '@c120.1~dark and stormy night.');
  assert.equal(placeAt(doc, 144), '@c144.1~', 'an image carries no text to vouch for');
  assert.equal(placeAt(doc, 151), '@c151.1~r the image');
  assert.equal(placeAt(doc, 110), '@c110.1~', 'between blocks: no quote');
  assert.equal(parse(placeAt(doc, 100)).quote.length, 32);
  assert.equal(blockText(doc.blocks[1]), 'It was a dark and stormy night.');
  assert.equal(blockText(doc.blocks[2]), null);
});

test('progress, percent and the chapter a place falls in', () => {
  assert.equal(progress({ length: 1000 }, '@c250.1~x'), 0.25);
  assert.equal(progress({ length: 1000 }, undefined), 0);
  assert.equal(progress({ fraction: 0.4 }, '@c1.1~'), 0.4);
  assert.equal(progress({}, undefined), null);
  assert.equal(percent(0.256), '26%');
  const ch = [{ title: 'a', anchor: '@c0.1~' }, { title: 'b', anchor: '@c500.1~' }, { title: 'c', anchor: '@c900.1~' }];
  assert.equal(partFor(ch, 0), 0);
  assert.equal(partFor(ch, 899), 1);
  assert.equal(partFor(ch, 5000), 2);
  assert.equal(partFor([], 5000), 0);
});
