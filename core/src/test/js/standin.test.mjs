// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-2, package JW0: the look harness's stand-in server — its WebSocket handshake and framing against RFC 6455's
// own example and lengths, and one round trip on loopback with node's WebSocket client: the bundle with the PC's
// policy, a scene's welcome and a description, a picture blob.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { acceptKey, frame, readFrames, standin } from './standin.mjs';
import { fixtures, SCENES } from './fixtures.mjs';

/** A client frame as a browser sends it: FIN, text, masked. */
function clientFrame(text, mask = [1, 2, 3, 4]) {
  const b = Buffer.from(text);
  const head = b.length < 126 ? Buffer.from([0x81, 0x80 | b.length]) : Buffer.from([0x81, 0x80 | 126, b.length >> 8, b.length & 255]);
  return Buffer.concat([head, Buffer.from(mask), Buffer.from(b.map((x, i) => x ^ mask[i & 3]))]);
}

test('the handshake answers RFC 6455\'s own example key', () => {
  assert.equal(acceptKey('dGhlIHNhbXBsZSBub25jZQ=='), 's3pPLMBiTxaQ9kYGzzhZRbK+xOo=');
});

test('a server frame carries its length in 7, 16 or 64 bits, unmasked', () => {
  assert.deepEqual([...frame('hi').subarray(0, 2)], [0x81, 2]);
  assert.deepEqual([...frame('x'.repeat(300)).subarray(0, 4)], [0x81, 126, 1, 44]);
  const big = frame(Buffer.alloc(70000), 2);
  assert.deepEqual([big[0], big[1]], [0x82, 127]);
  assert.equal(Number(big.readBigUInt64BE(2)), 70000);
  assert.equal(big.length, 10 + 70000);
});

test('client frames are unmasked whole, a partial one waits, an unmasked one is refused loudly', () => {
  const a = clientFrame('{"t":"ping","k":1}');
  const b = clientFrame('y'.repeat(200), [9, 8, 7, 6]);
  const both = Buffer.concat([a, b]);
  const r = readFrames(both.subarray(0, both.length - 5));
  assert.deepEqual(r.frames.map((f) => String(f.data)), ['{"t":"ping","k":1}']);
  assert.equal(r.rest.length, b.length - 5, 'the cut frame is kept for the next read');
  assert.equal(String(readFrames(Buffer.concat([r.rest, both.subarray(both.length - 5)])).frames[0].data), 'y'.repeat(200));
  assert.throws(() => readFrames(Buffer.from([0x81, 2, 104, 105])), /without a mask/);
});

test('the stand-in serves the bundle under the PC\'s policy and speaks the lane on loopback', async () => {
  const srv = await standin(SCENES.home, fixtures);
  try {
    const base = `http://127.0.0.1:${srv.port}`;
    const index = await fetch(`${base}/w/reader`);
    assert.equal(index.status, 200);
    assert.match(index.headers.get('content-security-policy'), /script-src 'self'/);
    assert.match(await index.text(), /<img id="look-hold" hidden/);
    assert.equal((await fetch(`${base}/js/app.mjs`)).headers.get('content-type'), 'text/javascript; charset=utf-8');
    assert.equal((await fetch(`${base}/js/nowhere.mjs`)).status, 404, 'only what files.txt lists');
    const blob = Buffer.from(await (await fetch(`${base}/blob/cover-b1`)).arrayBuffer());
    assert.deepEqual([...blob.subarray(1, 4)], [80, 78, 71], 'a PNG');
    const got = [];
    const ws = new WebSocket(`ws://127.0.0.1:${srv.port}/ws`);
    await new Promise((ok, no) => {
      ws.onopen = () => ws.send(JSON.stringify({ t: 'app', proto: 2, stream: 's1', contracts: { reader: 1 } }));
      ws.onerror = () => no(new Error('the stand-in\'s socket failed'));
      ws.onmessage = (e) => {
        const f = JSON.parse(e.data);
        got.push(f);
        if (f.t === 'welcome') ws.send(JSON.stringify({ t: 'sub', scope: 'desc/main' }));
        if (f.t === 'snap') ok();
      };
    });
    ws.close();
    assert.deepEqual(got.map((f) => f.t), ['welcome', 'snap']);
    assert.equal(got[0].conn, 7);
    assert.equal(got[1].desc.cards[0].window, 'reader');
    assert.deepEqual(srv.log.map((m) => m.t), ['app', 'sub']);
  } finally {
    await srv.close();
  }
});
