#!/usr/bin/env python3
"""Write the INPUTS of the v1 and v2 conformance vectors (`FIRMWARE.md` §9) into firmware/vectors/.

Context: Damage draws on Adam's G2 glasses through a custom firmware build (`FORK.md`).
Two implementations of the drawing contract must agree byte for byte: the firmware's
C (run on the PC by the fork's host harness, `~/damage-cfw/host/`) and Damage's Kotlin
simulator (`core/.../sim/GlassFirmwareSim.kt`). A vector is a message sequence and,
after each step, the CRC-32 of each lens's 640x480 packed shadow.

This script is clean-room Damage code: every message is built from the documented
v1 formats (`memory g2-cfw-mode-table`, `overview.md` §4, `core/.../wire/CfwModes.kt`),
never from the firmware C. It writes the steps with EMPTY expectations; the fork's
`host/run_vectors.py --write` fills them from the C, and `ConformanceVectorTest`
checks the simulator against them. v1 is the installed firmware, so for v1 the C is
the reference; a disagreement is a finding about the simulator or about the docs.

    python3 firmware/make_vectors.py            # rewrites firmware/vectors/v1-*.json and v2-*.json (inputs only)

Ops in a step: {"tick": ms} sets the firmware clock, {"lease": "acquire"|"release"}
sends the framebuffer lease op, {"msg": HEX} is one completed image message; Phase 2
(`FIRMWARE.md` §4): {"flags": N} is a FLAGS_SET of the whole set N, {"cachesize": KiB} the
CACHE_SIZE op. A step's expectation holds each lens's shadow CRC, the return codes, and the
refusal record (fields 23-25) and the status register (field 4) after the step, as
`"ref": {"L": [mode, reason, seq, status], "R": [...]}`.
"""
import json, pathlib, zlib

W, H = 640, 480
OUT = pathlib.Path(__file__).resolve().parent / "vectors"

def lcg(seed):
    x = seed & 0xFFFFFFFF
    while True:
        x = (x * 1664525 + 1013904223) & 0xFFFFFFFF
        yield x >> 16

def pattern(w, h, seed):
    """Levels 0..15 mixing long runs (every RLE token size) and noise."""
    g = lcg(seed)
    out = []
    while len(out) < w * h:
        r = next(g)
        if r % 5 == 0:
            out.extend([r % 16] * (1 + next(g) % 900))        # runs up to 900: the 2- and 4-byte tokens
        elif r % 5 == 1:
            out.extend([r % 16] * (1 + next(g) % 15))         # the 1-byte token
        else:
            out.extend(next(g) % 16 for _ in range(1 + next(g) % 12))
    return out[:w * h]

def nibbles_packed(levels, w, h):
    """Row-packed 4bpp as a nibble stream, high nibble = left pixel, a pad nibble
    ending each row when the width is odd (the modes 3/6 layout)."""
    out = []
    for y in range(h):
        row = levels[y * w:(y + 1) * w]
        out.extend(row)
        if w % 2: out.append(0)
    return out

def rle(nibs):
    """[cnt4|color4] 1..15 · [0|color4][cnt8] 1..255 · [0|color4][0][lo][hi] 1..65535."""
    out = bytearray()
    i = 0
    while i < len(nibs):
        c = nibs[i]
        j = i
        while j < len(nibs) and nibs[j] == c and j - i < 65535:
            j += 1
        n = j - i
        if n <= 15: out.append((n << 4) | c)
        elif n <= 255: out += bytes([c, n])
        else: out += bytes([c, 0, n & 0xFF, n >> 8])
        i = j
    return bytes(out)

def z(b): return zlib.compress(b, 6)
def u16(v): return bytes([v & 0xFF, (v >> 8) & 0xFF])

def keyframe(levels): return bytes([6]) + z(rle(nibbles_packed(levels, W, H)))

def delta(l, t, w, h, fid, levels):
    assert l % 4 == 0 and w % 4 == 0 and t % 2 == 0 and h % 2 == 0
    return bytes([3, l // 4, t // 2, w // 4, h // 2]) + u16(fid) + z(rle(nibbles_packed(levels, w, h)))

def delta_stereo(lL, lR, t, w, h, fid, levels):
    box = lambda l: bytes([l // 4, t // 2, w // 4, h // 2])
    return bytes([0x83]) + box(lL) + box(lR) + u16(fid) + z(rle(nibbles_packed(levels, w, h)))

def copy(s, d):
    return bytes([9]) + b"".join(u16(v) for v in (*s, *d))

def copy_stereo(sL, dL, sR, dR):
    return bytes([0x89]) + b"".join(u16(v) for v in (*sL, *dL, *sR, *dR))

def batch(*subs):
    return bytes([8, len(subs)]) + b"".join(u16(len(s)) + s for s in subs)

def cache_image(w, h, levels):
    """[w:u8][h:u8][RLE of exactly w*h pixels] — no row pad nibble."""
    return bytes([w, h]) + rle(levels)

def cache_write(*entries):
    return bytes([12]) + b"".join(u16(off) + u16(len(data)) + data for off, data in entries)

def draw_image(off, x, y, options): return bytes([13]) + u16(off) + u16(x) + u16(y) + bytes([options])

def draw_string(font, x, y, options, text):
    return bytes([14]) + u16(font) + u16(x) + u16(y) + bytes([options, len(text)]) + text

# ---- Phase 2, the drawing contract v2 (`FIRMWARE.md` §4) --------------------------------
def s16(v):
    assert -32768 <= v <= 32767
    return u16(v & 0xFFFF)
def rect(l, t, w, h): return b"".join(u16(v) for v in (l, t, w, h))
def image2(w, h, levels):
    """A v2 image record: [w u16][h u16][RLE of exactly w*h pixels], no row pad."""
    return u16(w) + u16(h) + rle(levels)
def draw2(off4, x, y, options): return bytes([17]) + u16(off4) + s16(x) + s16(y) + bytes([options])
def draw2_pair(off4, xL, xR, y, options): return bytes([0x91]) + u16(off4) + s16(xL) + s16(xR) + s16(y) + bytes([options])
def string2(font4, x, y, options, text): return bytes([18]) + u16(font4) + s16(x) + s16(y) + bytes([options, len(text)]) + text
def string2_pair(font4, xL, xR, y, options, text):
    return bytes([0x92]) + u16(font4) + s16(xL) + s16(xR) + s16(y) + bytes([options, len(text)]) + text
def cache_write2(*entries): return bytes([19]) + b"".join(u16(off4) + u16(len(data)) + data for off4, data in entries)
def clip(r): return bytes([20]) + r
def clip_pair(rL, rR): return bytes([0x94]) + rL + rR
def fill(r, level): return bytes([21]) + r + bytes([level])
def fill_pair(rL, rR, level): return bytes([0x95]) + rL + rR + bytes([level])
def lut_bytes(table):
    """16 entries packed two per byte, entry i in nibble i, high nibble first."""
    assert len(table) == 16
    return bytes([(table[i] << 4) | table[i + 1] for i in range(0, 16, 2)])
def lut(r, table): return bytes([22]) + r + lut_bytes(table)
def lut_pair(rL, rR, table): return bytes([0x96]) + rL + rR + lut_bytes(table)
def capture(slot, r): return bytes([23, 0, slot]) + r
def capture_pair(slot, rL, rR): return bytes([0x97, 0, slot]) + rL + rR
def restore(slot): return bytes([23, 1, slot])
def free_slot(slot): return bytes([23, 2, slot])
def hint(y0, y1): return bytes([24]) + u16(y0) + u16(y1)
DRAW2 = 0x0004
def flags(n): return {"flags": n}
def cachesize(kib): return {"cachesize": kib}

def runs_pattern(w, h, seed):
    """Levels 0..15 in long runs (40..300 px) with a few noisy patches: a big image whose
    RLE stays small enough for one message."""
    g = lcg(seed)
    out = []
    while len(out) < w * h:
        r = next(g)
        if r % 7 == 0:
            out.extend(next(g) % 16 for _ in range(1 + next(g) % 6))
        else:
            out.extend([r % 16] * (40 + next(g) % 260))
    return out[:w * h]

class Cache2:
    """Packs v2 records 4-byte aligned; offsets come back in 4-byte units (off4)."""
    def __init__(self, start=64): self.buf = bytearray(start); self.entries = []
    def add(self, data):
        while len(self.buf) % 4: self.buf.append(0)
        off = len(self.buf)
        self.buf += data
        self.entries.append((off // 4, bytes(data)))
        return off // 4
    def reserve(self, n):
        while len(self.buf) % 4: self.buf.append(0)
        off = len(self.buf); self.buf += bytes(n); return off // 4
    def put(self, off4, data):
        self.buf[off4 * 4:off4 * 4 + len(data)] = data
        self.entries.append((off4, bytes(data)))
    def messages(self, chunk=3000):
        """Mode-19 messages writing every entry, in order, each under `chunk` bytes of data."""
        out, cur, size = [], [], 0
        for off4, data in self.entries:
            pos = 0
            while pos < len(data):
                n = min(chunk, len(data) - pos)
                if size + n > chunk and cur:
                    out.append(cache_write2(*cur)); cur, size = [], 0
                cur.append((off4 + pos // 4, data[pos:pos + n])); size += n
                pos += n
                assert pos == len(data) or n % 4 == 0
        if cur: out.append(cache_write2(*cur))
        return out

def step(*ops): return {"ops": list(ops), "expect": {}}
def msg(b): return {"msg": b.hex()}

def vectors():
    v = []
    kf = keyframe(pattern(W, H, 1))
    box = lambda w, h, s: pattern(w, h, s)

    v.append({"name": "v1-keyframe", "steps": [
        step({"tick": 1000}, msg(kf)),
        step(msg(keyframe(pattern(W, H, 2)))),
    ]})
    v.append({"name": "v1-delta", "steps": [
        step({"tick": 1000}, msg(kf)),
        step(msg(delta(40, 60, 200, 100, 1, box(200, 100, 3)))),
        step(msg(delta(0, 0, 640, 2, 2, box(640, 2, 4)))),               # the full-width top row pair
        step(msg(delta(636, 478, 4, 2, 3, box(4, 2, 5)))),               # the last cell
        step(msg(delta_stereo(100, 108, 300, 120, 40, 4, box(120, 40, 6)))),
    ]})
    v.append({"name": "v1-copy", "steps": [
        step({"tick": 1000}, msg(kf)),
        step(msg(copy((40, 100, 320, 200), (40, 104, 320, 200)))),     # overlapping, downward
        step(msg(copy((40, 104, 320, 200), (40, 100, 320, 200)))),     # overlapping, upward
        step(msg(copy((1, 3, 333, 77), (6, 5, 333, 77)))),             # odd coordinates: the nibble path
        step(msg(copy_stereo((0, 200, 600, 80), (8, 200, 600, 80), (32, 200, 600, 80), (24, 200, 600, 80)))),
    ]})
    v.append({"name": "v1-batch", "steps": [
        step({"tick": 1000}, msg(kf)),
        step(msg(batch(copy((0, 40, 640, 400), (0, 0, 640, 400)), delta(0, 400, 640, 40, 1, box(640, 40, 7))))),
        step(msg(batch(delta(200, 100, 40, 20, 2, box(40, 20, 8)),
                       delta(300, 100, 40, 20, 3, box(40, 20, 9)),
                       copy((200, 100, 40, 20), (400, 300, 40, 20))))),
    ]})
    v.append({"name": "v1-refusals", "steps": [
        step({"tick": 1000}, msg(kf)),
        step(msg(delta(600, 0, 80, 20, 1, box(80, 20, 10)))),          # out of bounds: refused, shadow kept
        step(msg(copy((0, 0, 100, 100), (600, 0, 100, 100)))),         # copy out of bounds
        step(msg(delta(8, 8, 16, 8, 2, box(16, 8, 11)))),              # fid 2
        step(msg(delta(8, 8, 16, 8, 2, box(16, 8, 12)))),              # fid 2 again: skipped
        # a batch whose second sub-message is not a shadow op: the first sub has already
        # changed the shadow when the batch is refused
        step(msg(batch(delta(80, 80, 40, 40, 3, box(40, 40, 13)), bytes([5, 2])))),
    ]})
    glyphs = {ch: cache_image(6 + (ch % 5), 12, pattern(6 + (ch % 5), 12, 100 + ch)) for ch in range(32, 128)}
    table_off, pos = 1000, 1000 + 96 * 2
    table, images = bytearray(), []
    for ch in range(32, 128):
        table += u16(pos)
        images.append((pos, glyphs[ch]))
        pos += len(glyphs[ch])
    icon = cache_image(40, 30, pattern(40, 30, 14))
    v.append({"name": "v1-cache", "steps": [
        step({"tick": 1000}, msg(kf), {"lease": "acquire"}),
        step(msg(cache_write((0, icon), (table_off, bytes(table)), *images))),
        step(msg(draw_image(0, 100, 100, 0x0F))),                      # identity ramp
        step(msg(draw_image(0, 150, 100, 0x18))),                      # top 8, source 0 transparent
        step(msg(draw_image(0, 200, 100, 0x2C))),                      # top 12, reversed
        step(msg(draw_image(0, 620, 470, 0x0F))),                      # clipped at the right and bottom
        step(msg(draw_string(table_off, 20, 300, 0x1F, b"Damage 0.45" + bytes([13]) + b"!"))),   # x adjust +2
        step(msg(draw_string(table_off, 20, 320, 0x0F, b"bad\x80"))),  # a byte above 127: nothing drawn
    ]})
    v.append({"name": "v1-lease", "steps": [
        step({"tick": 1000}, msg(kf), {"lease": "acquire"}),
        step(msg(cache_write((0, icon)))),
        step(msg(draw_image(0, 10, 10, 0x0F))),
        step({"tick": 60000}, {"lease": "acquire"}, msg(draw_image(0, 60, 10, 0x0F))),   # a renewal keeps the cache
        step({"tick": 200000}, msg(draw_image(0, 110, 10, 0x0F))),     # lapsed: refused, cache freed
        step({"lease": "acquire"}, msg(draw_image(0, 160, 10, 0x0F))), # a fresh lease: the cache is gone
    ]})
    for vec in v:
        vec["contract"] = 1
        vec["start"] = "zero"
    return v + vectors_v2(kf)

def vectors_v2(kf):
    """The v2 set (`FIRMWARE.md` §4): every op, its per-lens form, a refusal per reason."""
    v = []
    arm = [{"tick": 1000}, msg(kf), {"lease": "acquire"}, flags(DRAW2)]

    # a cache: two images, a 224-entry font, a v1 record for the v1 window checks
    c = Cache2()
    icon = c.add(image2(40, 30, pattern(40, 30, 21)))
    tall = c.add(image2(24, 100, pattern(24, 100, 22)))
    glyphs = {ch: cache_image(5 + (ch % 7), 14, pattern(5 + (ch % 7), 14, 200 + ch)) for ch in range(32, 256)}
    table4 = c.reserve(224 * 2)
    goff = {ch: c.add(glyphs[ch]) for ch in range(32, 256)}
    c.put(table4, b"".join(u16(goff[ch]) for ch in range(32, 256)))
    bad_table4 = c.reserve(224 * 2)                      # every entry points at offset 0 (zeros: w = 0)
    writes = [msg(m) for m in c.messages()]

    v.append({"name": "v2-perlens", "steps": [
        step(*arm, *writes),
        step(msg(draw2(icon, 100, 100, 0x0F))),                                   # one x
        step(msg(draw2_pair(icon, 200, 216, 100, 0x0F))),                         # two x's: L at 200, R at 216
        step(msg(draw2_pair(icon, -8, 8, 200, 0x1F))),                            # a negative x on the left lens, transparent
        step(msg(draw2(tall, 300, -50, 0x0F))),                                   # a negative y
        step(msg(string2(table4, 20, 300, 0x0F, b"Damage 2"))),
        step(msg(string2_pair(table4, 20, 36, 340, 0x1F, b"Damage 2 \xe9\xff" + bytes([21]) + b"!"))),   # Latin-1, an adjust of +10
        step(msg(string2_pair(table4, -12, -4, 380, 0x0F, b"A"))),
    ]})

    big = Cache2(start=61440)                                      # the records straddle the v1 window's end
    mid = big.add(image2(300, 300, runs_pattern(300, 300, 31)))
    huge = big.add(image2(640, 600, runs_pattern(640, 600, 32)))
    assert len(big.buf) > 65536, len(big.buf)                        # the 160 KiB cache is what makes it fit
    v.append({"name": "v2-image16", "steps": [
        step(*arm, cachesize(160), *[msg(m) for m in big.messages()]),
        step(msg(draw2(mid, 100, 50, 0x0F))),
        step(msg(draw2(mid, 500, 300, 0x0F))),                                     # clipped right and bottom
        step(msg(draw2(huge, 0, -100, 0x0F))),                                     # a 640x600 record scrolled up 100 rows
        step(msg(draw2(huge, -300, -200, 0x2F))),                                  # clipped on two sides, inverse ramp
        step(msg(draw2(mid, 700, 0, 0x0F))),                                       # wholly off the panel: accepted, draws nothing
    ]})

    v.append({"name": "v2-clip", "steps": [
        step(*arm, *writes),
        step(msg(batch(clip(rect(50, 50, 100, 60)), draw2(icon, 40, 40, 0x0F), fill(rect(0, 0, 640, 480), 3),
                       string2(table4, 30, 90, 0x0F, b"clipped")))),
        step(msg(batch(fill(rect(0, 200, 640, 20), 9)))),                          # the clip died with its batch
        step(msg(clip(rect(0, 0, 64, 64)))),                                       # outside a batch: refused (10)
        step(msg(batch(clip_pair(rect(0, 300, 200, 100), rect(400, 300, 200, 100)), fill(rect(0, 0, 640, 480), 12)))),
        step(msg(batch(clip(rect(600, 0, 41, 10)), fill(rect(0, 0, 8, 8), 1)))),    # a clip past the panel: refused (2), the batch with it
        step(msg(batch(clip(rect(100, 100, 50, 50)), clip(rect(120, 120, 50, 50)), fill(rect(0, 0, 640, 480), 15)))),   # the later clip wins
    ]})

    v.append({"name": "v2-fill", "steps": [
        step(*arm),
        step(msg(fill(rect(1, 3, 333, 77), 15))),                                  # odd edges: the nibble path
        step(msg(fill_pair(rect(0, 200, 300, 40), rect(16, 200, 300, 40), 7))),
        step(msg(fill(rect(636, 476, 4, 4), 0))),                                  # the last cell
        step(msg(fill(rect(0, 0, 8, 8), 16))),                                     # level 16: refused (12)
        step(msg(fill(rect(600, 0, 41, 10), 5))),                                  # past the panel: refused (2)
        step(msg(fill(rect(0, 0, 0, 10), 5))),                                     # empty: refused (2)
        step(msg(batch(fill(rect(0, 400, 640, 80), 4), fill(rect(0, 0, 640, 480), 2)))),
    ]})

    dim = [i // 2 for i in range(16)]
    inv = [15 - i for i in range(16)]
    bright = [min(15, i + 4) for i in range(16)]
    v.append({"name": "v2-lut", "steps": [
        step(*arm),
        step(msg(lut(rect(0, 0, 640, 480), dim))),
        step(msg(lut(rect(100, 100, 201, 101), inv))),
        step(msg(lut_pair(rect(0, 300, 320, 100), rect(320, 300, 320, 100), bright))),
        step(msg(lut(rect(0, 470, 640, 20), inv))),                                # past the bottom: refused (2)
        step(msg(batch(clip(rect(50, 50, 100, 100)), lut(rect(0, 0, 640, 480), inv)))),   # under a clip
    ]})

    v.append({"name": "v2-saveunder", "steps": [
        step(*arm),
        step(msg(capture(0, rect(100, 100, 201, 55)))),                            # capture slot 0 (odd width)
        step(msg(fill(rect(0, 0, 640, 480), 9))),
        step(msg(restore(0))),                                                     # the shadow's rect comes back
        step(msg(capture_pair(1, rect(0, 300, 320, 100), rect(320, 300, 320, 100)))),
        step(msg(fill(rect(0, 280, 640, 140), 3))),
        step(msg(batch(restore(1), fill(rect(0, 0, 8, 8), 0)))),
        step(msg(free_slot(1))),
        step(msg(restore(1))),                                                     # empty: refused (7)
        step(msg(restore(4))),                                                     # no such slot: refused (7)
        step(msg(capture(2, rect(0, 0, 640, 160)))),                               # 51,200 B: over the 49,152 B pool: refused (7)
        step(msg(capture(2, rect(0, 0, 640, 120)))),                               # 38,400 B with slot 0's 5,555 = 43,955: fits
        step(msg(capture(0, rect(0, 0, 640, 40)))),                                # replacing slot 0: 38,400 + 12,800 = 51,200: refused (7)
        step(msg(capture(0, rect(0, 0, 640, 17)))),                                # replacing slot 0: 38,400 + 5,440 = 43,840 fits (49,395 if the old bytes counted)
        step(msg(capture(3, rect(0, 0, 640, 2)))),                                 # 640 B more: 44,480, fits
        step(msg(capture(3, rect(0, 0, 640, 16)))),                                # replacing slot 3: 43,840 + 5,120 = 48,960, fits
        step(msg(capture(1, rect(0, 0, 640, 1)))),                                 # 320 B more: 49,280, refused (7)
        step(msg(bytes([23, 3, 0]))),                                              # sub 3: refused (12)
        step(msg(free_slot(1))),                                                   # an empty slot's free: fine
    ]})

    v.append({"name": "v2-font224", "steps": [
        step(*arm, *writes),
        step(msg(string2(table4, 10, 10, 0x0F, bytes(range(32, 128))))),           # the ASCII half, one line
        step(msg(string2(table4, 10, 40, 0x0F, bytes(range(128, 256))))),          # the Latin-1 half
        step(msg(string2(table4, 10, 70, 0x0F, b"A" + bytes([1]) + b"B" + bytes([31]) + b"C"))),   # adjusts -10 and +20
        step(msg(string2(table4, 10, 100, 0x0F, b"A\x00B"))),                     # a 0 byte: refused (6)
        step(msg(string2(bad_table4, 10, 130, 0x0F, b"A"))),                       # an entry at a zero record: refused (5)
        step(msg(string2(0xFFF0, 10, 160, 0x0F, b"A"))),                           # a table past the cache: refused (5)
        step(msg(string2(table4, 10, 190, 0x0F, b""))),                            # an empty string: nothing drawn, accepted
    ]})

    far = Cache2(start=80000)
    faroff = far.add(image2(40, 30, pattern(40, 30, 41)))
    v1rec = cache_image(20, 20, pattern(20, 20, 42))
    v.append({"name": "v2-cachesize", "steps": [
        step({"tick": 1000}, msg(kf), cachesize(128)),                             # no lease: status 3, nothing set
        step({"lease": "acquire"}, flags(DRAW2), cachesize(0)),                    # zero: status 5
        step(cachesize(161)),                                                      # over the budget: status 5
        step(cachesize(128)),                                                      # ok: status 0
        step(*[msg(m) for m in far.messages()]),                                   # a write at 80,000: inside the 128 KiB
        step(msg(draw2(faroff, 10, 10, 0x0F))),
        step(cachesize(64)),                                                       # allocated already: status 4
        step(msg(cache_write((65000, v1rec)))),                                    # a v1 write past the v1 window: refused (5)
        step(msg(cache_write((100, v1rec))), msg(draw_image(100, 60, 10, 0x0F))),  # the v1 window works as before
        step({"lease": "release"}, {"lease": "acquire"}, flags(DRAW2), *[msg(m) for m in far.messages()]),   # a fresh lease: 64 KiB again, refused (5)
    ]})

    v.append({"name": "v2-refusals", "steps": [
        step(*arm, *writes),
        step(msg(bytes([17, 0, 0]))),                                              # short: 1
        step(msg(fill(rect(632, 0, 16, 16), 1))),                                  # bounds: 2
        step(msg(draw2(0xFFFF, 0, 0, 0x0F))),                                      # a record past the cache: 5
        step(msg(string2(table4, 0, 0, 0x0F, b"\x00"))),                          # code: 6
        step(msg(restore(2))),                                                     # scratch: 7
        step(msg(batch(fill(rect(0, 0, 8, 8), 1), cache_write2((0, b"\x00" * 4))))),   # a cache write in a batch: 8, the fill already applied
        step(msg(bytes([25, 0, 0, 0]))),                                           # an unknown mode: 8, then the stock BMP path
        step(msg(hint(0, 10))),                                                    # outside a batch: 10
        step(msg(bytes([23, 5, 0]))),                                              # value: 12
        step(msg(bytes([3, 0, 0, 4, 4, 9, 0, 0x78, 0x9c, 0, 0, 0, 0]))),           # a mode-3 whose stream does not decode: 13
        step(msg(copy((0, 0, 100, 100), (600, 0, 100, 100)))),                     # v1: a copy out of bounds: mode 9 reason 2
        step(msg(cache_write((0, b"\x01\x02")) + b"\x00")),                     # v1: a truncated mode-12 entry: 1
        step(msg(batch(fill(rect(0, 0, 640, 480), 6), bytes([5, 2])))),            # a batch with a sound sub-message: 8
        step(msg(batch(bytes([17, 0, 0])))),                                       # a batch whose sub is short: the sub's 1
        step(msg(fill(rect(0, 0, 8, 8), 2))),                                      # accepted: the record stays the last refusal
    ]})

    v.append({"name": "v2-flags", "steps": [
        step({"tick": 1000}, msg(kf), flags(DRAW2)),                               # no lease: status 3, nothing armed
        step(msg(fill(rect(0, 0, 100, 100), 15))),                                 # no lease: 3
        step({"lease": "acquire"}, msg(fill(rect(0, 0, 100, 100), 15))),           # DRAW2 unarmed: 9
        step(flags(DRAW2), msg(fill(rect(0, 0, 100, 100), 15))),                   # armed: draws
        step(flags(0), msg(fill(rect(0, 200, 100, 100), 15))),                     # disarmed: 9
        step(flags(DRAW2 | 0x8000), msg(fill(rect(0, 200, 100, 100), 15))),        # with PROBE: draws
        step({"lease": "release"}, msg(fill(rect(0, 300, 100, 100), 15))),         # released: the flags cleared, no lease: 3
        step({"lease": "acquire"}, msg(fill(rect(0, 300, 100, 100), 15))),         # a fresh lease: still unarmed: 9
    ]})

    v.append({"name": "v2-hint", "steps": [
        step(*arm),
        step(msg(batch(hint(100, 139), fill(rect(0, 100, 640, 40), 8)))),          # the rows the fill touched
        step(msg(batch(fill(rect(0, 0, 640, 480), 2), hint(0, 479)))),             # a whole-panel hint (the order in the batch is free)
        step(msg(batch(hint(200, 100), fill(rect(0, 0, 8, 8), 1)))),               # y0 > y1: refused (2)
        step(msg(batch(hint(0, 480), fill(rect(0, 0, 8, 8), 1)))),                 # y1 past the panel: refused (2)
        step(msg(batch(hint(10, 20), hint(30, 40), fill(rect(0, 0, 640, 480), 5)))),   # the later hint wins
    ]})
    for vec in v:
        vec["contract"] = 2
        vec["start"] = "zero"
    return v

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for vec in vectors():
        p = OUT / f"{vec['name']}.json"
        p.write_text(json.dumps(vec, indent=1) + "\n")
        size = p.stat().st_size
        print(f"wrote {p.name}: {len(vec['steps'])} steps, {size:,} B")

if __name__ == "__main__":
    main()
