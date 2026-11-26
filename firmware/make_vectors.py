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

    python3 firmware/make_vectors.py                 # writes every vector's inputs (v1-*, v2-*, v3-*)
    python3 firmware/make_vectors.py --only v3       # writes only the v3-* files; the rest are left as they are
    python3 firmware/make_vectors.py --only v5       # C3's set (FIRMWARE.md §7.13): the v5-* inputs alone
    python3 firmware/make_vectors.py --check         # the C1 builders' self-check; writes nothing

A file that already exists keeps the expectations of the longest prefix of steps whose inputs (and vector-level
keys) did not change: those are the C's answers to the same input. Every step from the first changed one on is
written with an empty expectation, for the fork's `host/run_vectors.py --write` to fill.

Ops in a step: {"tick": ms} sets the firmware clock, {"lease": "acquire"|"release"}
sends the framebuffer lease op, {"msg": HEX} is one completed image message; Phase 2
(`FIRMWARE.md` §4): {"flags": N} is a FLAGS_SET of the whole set N, {"cachesize": KiB} the
CACHE_SIZE op. A step's expectation holds each lens's shadow CRC, the return codes, and the
refusal record (fields 23-25) and the status register (field 4) after the step, as
`"ref": {"L": [mode, reason, seq, status], "R": [...]}`.

C1 (`FIRMWARE.md` §5.10, the v3-* set): {"wake": true} is one engine wake at the clock the last `tick` op set; a
vector's "features" key is the DamageCaps features of the build it is for (0x1ff). The C adds the expectations
"N" (RIGHT's notify-114 bodies, fields 9 and 11 dropped), "W" (each lens's planned wait) and "G" (field 17).
"""
import json, pathlib, struct, zlib
from itertools import combinations_with_replacement

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

def stock(): return {"stock": True}          # the stock compositor's own repaint (`FIRMWARE.md` §9)
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
    # A stream that does not decode to its size: the firmware inflates and RLE-decodes STRAIGHT
    # INTO the shadow (or into the box), so a refusal leaves the pixels it had already written —
    # it is not a rollback, whatever "the old frame is kept" suggests. Three shapes, each with a
    # different cause: the zlib stream cut short, an RLE that ends before the box is full, and one
    # that runs past it. The step after each shows the shadow carrying on from the partial state.
    # (2026-09-16 review: a differential fuzz of the C against the simulator found the model
    # rolling back where the glasses cannot.)
    bx = pattern(80, 40, 21)
    good = delta(100, 100, 80, 40, 1, bx)
    short_rle = bytes([3, 100 // 4, 100 // 2, 80 // 4, 40 // 2]) + u16(2) + z(rle(nibbles_packed(bx, 80, 40))[:60])
    long_rle = bytes([3, 100 // 4, 100 // 2, 80 // 4, 40 // 2]) + u16(3) + z(rle(nibbles_packed(bx + bx, 80, 80)))
    kf_short = bytes([6]) + z(rle(nibbles_packed(pattern(W, H, 22), W, H))[:4000])
    v.append({"name": "v1-stream", "steps": [
        step({"tick": 1000}, msg(kf)),
        step(msg(good[:len(good) - 12])),                                # the zlib stream cut short
        step(msg(delta(300, 200, 40, 20, 4, box(40, 20, 23)))),          # the shadow carries on
        step(msg(short_rle)),                                            # decodes short of the box
        step(msg(long_rle)),                                             # runs past the box
        step(msg(kf_short)),                                             # a keyframe cut short
        step(msg(keyframe(pattern(W, H, 24)))),                          # and a whole one after it
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
    # `HANDOFF.md` §73, the v1 twin of `v2-tablepatch`: a face packed with only the glyphs it draws,
    # gaining more IN PLACE on the upstream contract — a mode-12 offset is a byte offset, so one
    # 2-byte table entry is rewritten on its own, wherever the table starts (here an ODD offset).
    # Every entry the face does not pack names one shared tofu record.
    ptofu = cache_image(7, 12, [15 if (x in (0, 6) or y in (0, 11)) else 0 for y in range(12) for x in range(7)])
    pg = {ch: cache_image(5 + (ch % 7), 12, pattern(5 + (ch % 7), 12, 400 + ch)) for ch in b"ABCD~"}
    ptable = 1001                                                       # odd: an entry is two unaligned bytes
    ppos = ptable + 96 * 2
    poff = {}
    for ch in b"AB":
        poff[ch] = ppos; ppos += len(pg[ch])
    def pentry(ch, off): return (ptable + (ch - 32) * 2, u16(off))
    c_off = ppos; tilde_off = c_off + len(pg[ord("C")])
    v.append({"name": "v1-tablepatch", "steps": [
        step({"tick": 1000}, msg(kf), {"lease": "acquire"}),
        step(msg(cache_write((0, ptofu), (ptable, b"".join(u16(poff.get(ch, 0)) for ch in range(32, 128))),
                             *[(poff[ch], pg[ch]) for ch in b"AB"]))),
        step(msg(draw_string(ptable, 20, 20, 0x0F, b"AB"))),             # the two packed glyphs
        step(msg(draw_string(ptable, 20, 40, 0x0F, b"ABC"))),            # C is not packed: its entry names the tofu, and the tofu is drawn
        step(msg(cache_write((c_off, pg[ord("C")]), pentry(ord("C"), c_off)))),   # ONE message: C's record appended and its 2-byte entry rewritten
        step(msg(draw_string(ptable, 20, 60, 0x0F, b"ABC"))),            # C from its own record; A and B exactly as before
        step(msg(cache_write(pentry(ord("D"), poff[ord("A")])))),        # nothing appended: D's entry names A's record, already in the cache
        step(msg(draw_string(ptable, 20, 80, 0x1F, b"DAD"))),
        step(msg(cache_write((tilde_off, pg[ord("~")]), pentry(ord("~"), tilde_off)))),   # the last code a run may hold (126), its record in the same message
        step(msg(draw_string(ptable, 20, 100, 0x0F, b"A~B"))),
        step(msg(cache_write(pentry(ord("A"), 0), (65530, bytes(8)))),   # a message whose last entry leaves the 64 KiB cache is refused WHOLE (5): its table patch is not applied...
             msg(draw_string(ptable, 20, 120, 0x0F, b"AB"))),            # ...so A still draws its glyph
    ]})
    v.append({"name": "v1-lease", "steps": [
        step({"tick": 1000}, msg(kf), {"lease": "acquire"}),
        step(msg(cache_write((0, icon)))),
        step(msg(draw_image(0, 10, 10, 0x0F))),
        step({"tick": 60000}, {"lease": "acquire"}, msg(draw_image(0, 60, 10, 0x0F))),   # a renewal keeps the cache
        step({"tick": 200000}, msg(draw_image(0, 110, 10, 0x0F))),     # lapsed: refused, cache freed
        step({"lease": "acquire"}, msg(draw_image(0, 160, 10, 0x0F))), # a fresh lease: the cache is gone
    ]})

    # ---- the vector pass (2026-11-12, APK 0.65; the design in the build's scratch notes) ----------
    # The mutation sweep of 2026-11-10 found refusal guards no gate proved: the v1 ops' lengths, the
    # batch's own structure, the v1 cache ops without the lease, modes 10 and 16's sub checks, mode
    # 15's checks before the font, the batch's size limit. Each step below is written from
    # `FIRMWARE.md` §4 ("the v1 modes' order as built") and asks one of them; the C's answers come
    # from the fork's `run_vectors.py --write`. Rules kept: a vector with a no-lease step carries a
    # release and so has no self-test form; a zero record is WRITTEN, never assumed (the glasses' live
    # cache is not zeroed — `v2-badrec`); no step's answer depends on bytes after its message.
    sv_icon2 = cache_image(40, 30, pattern(40, 30, 15))                 # icon's size, other pixels: what a refused write must not leave
    sv_gl = {ch: cache_image(6 + (ch % 5), 12, pattern(6 + (ch % 5), 12, 500 + ch)) for ch in b"AB"}
    t_off = 1000
    sv_pos = t_off + 96 * 2
    sv_goff = {}
    for ch in b"AB":
        sv_goff[ch] = sv_pos; sv_pos += len(sv_gl[ch])
    zero_off = sv_pos                                                   # a record of w 0, written below
    sv_table = b"".join(u16(sv_goff.get(ch, zero_off)) for ch in range(32, 128))   # A and B name glyphs, every other code the zero record
    v1cache = cache_write((0, icon), (t_off, sv_table), *[(sv_goff[ch], sv_gl[ch]) for ch in b"AB"], (zero_off, bytes(4)))
    sub = copy((0, 0, 64, 64), (320, 200, 64, 64))
    sub3 = copy((0, 0, 64, 64), (400, 300, 64, 64))
    cp = copy((0, 0, 100, 100), (200, 200, 100, 100))
    sbox = pattern(120, 40, 6)
    stereo = delta_stereo(100, 108, 300, 120, 40, 1, sbox)
    stereo_sizes = (bytes([0x83, 100 // 4, 300 // 2, 120 // 4, 40 // 2, 108 // 4, 300 // 2, 124 // 4, 40 // 2])
                    + u16(1) + z(rle(nibbles_packed(sbox, 120, 40))))   # the right box 4 px wider
    # The v1 ops' lengths, the batch's own structure, and "checked whole before a byte is written or a
    # pixel drawn" for modes 12 and 14. Lease held throughout: it has a self-test form.
    v.append({"name": "v1-shape", "steps": [
        step({"tick": 1000}, msg(kf), {"lease": "acquire"}, msg(v1cache)),
        # mode 13: exactly 7 bytes after the mode byte
        step(msg(draw_image(0, 100, 100, 0x0F) + b"\x00")),                 # 1  one byte long: 1, nothing drawn
        step(msg(draw_image(0, 100, 100, 0x0F)[:-1])),                      # 2  one byte short: 1
        step(msg(draw_image(0, 100, 100, 0x0F))),                           # 3  whole: lands
        # mode 14: the header, then exactly the declared string; every glyph checked before a pixel
        step(msg(draw_string(t_off, 20, 300, 0x0F, b"AB") + b"A")),         # 4  a byte after the declared string: 1
        step(msg(draw_string(t_off, 20, 300, 0x0F, b"AB")[:-1])),           # 5  a byte short of it: 1
        step(msg(draw_string(t_off, 20, 300, 0x0F, b"AB")[:6])),            # 6  shorter than the header: 1
        step(msg(draw_string(t_off, 20, 300, 0x0F, b"AZ"))),                # 7  Z names the zero record: 5, and A is not drawn
        step(msg(draw_string(t_off, 20, 300, 0x0F, b"AB"))),                # 8  whole: lands
        # mode 9: at least its rects; one size; not empty
        step(msg(cp[:-1])),                                                 # 9  one byte short: 1
        step(msg(bytes([0x89]) + cp[1:])),                                  # 10 the per-lens form with one rect set: 1 on both lenses
        step(msg(cp + b"\x00")),                                            # 11 one byte long: accepted (the check is "at least")
        step(msg(copy((0, 0, 100, 100), (300, 200, 100, 90)))),             # 12 two sizes: 2
        step(msg(copy((0, 0, 0, 10), (300, 200, 0, 10)))),                  # 13 an empty rect: 2
        # mode 3, the per-lens form
        step(msg(stereo[:11])),                                             # 14 both boxes and the fid, no stream: 1
        step(msg(stereo_sizes)),                                            # 15 two box sizes: 2, before the fid is taken
        step(msg(stereo)),                                                  # 16 whole, the same fid: lands (the refusals left the fid ring alone)
        # mode 8: the batch's own structure
        step(msg(bytes([8]))),                                              # 17 no count: 1
        step(msg(bytes([8, 1, 0, 0]))),                                     # 18 a sub-message of length 0: 1
        step(msg(bytes([8, 1]) + u16(len(sub) + 5) + sub)),                 # 19 a length running past the message: 1, the sub not applied
        step(msg(bytes([8, 2]) + u16(len(sub)) + sub)),                     # 20 count 2, one sub: the sub lands, then 1
        step(msg(bytes([8, 1]) + u16(len(sub3)) + sub3 + b"\xEE" * 3)),     # 21 bytes after the last counted sub: not read, the sub lands
        step(msg(bytes([8, 0]))),                                           # 22 an empty batch: accepted
        # mode 12: the whole list is checked before a byte is written
        step(msg(cache_write((0, sv_icon2)) + b"\x00\x00\x00"),             # 23 a sound entry, then 3 bytes of a header: 1 ...
             msg(draw_image(0, 300, 100, 0x0F))),                           #    ... and the sound entry was not written: the first icon draws
        step(msg(cache_write((0, sv_icon2)) + u16(4000) + u16(100) + bytes(4)),   # 24 a sound entry, then data running past the message: 1
             msg(draw_image(0, 360, 100, 0x0F))),                           #    the first icon again
        step(msg(cache_write((0, sv_icon2))), msg(draw_image(0, 420, 100, 0x0F))),   # 25 the entry alone: written, the second icon draws
    ]})
    # The v1 twin of `v2-gates`: every v1 cache op with no lease, the order of its length check against
    # the lease, a draw after a fresh lease (the cache went with the release), and the two modes that
    # draw nothing (10 and 16). Two releases: no self-test form.
    v.append({"name": "v1-gates", "steps": [
        step({"tick": 1000}, msg(kf), {"lease": "acquire"}, msg(v1cache)),
        step(msg(draw_string(t_off, 20, 20, 0x0F, b"AB"))),                 # 1  the lease held: draws
        step({"lease": "release"}, msg(cache_write((0, sv_icon2)))),        # 2  mode 12 with data, no lease: 3
        step(msg(cache_write((0, b"")))),                                   # 3  a list with no data: accepted before the lease is asked
        step(msg(cache_write((0, sv_icon2)) + b"\x00")),                    # 4  its shape (1) before the lease
        step(msg(cache_write((65530, bytes(8))))),                          # 5  the window (5) before the lease
        step(msg(draw_image(0, 10, 10, 0x0F))),                             # 6  mode 13: 3 (v1-lease asks it after a lapse; this is a release)
        step(msg(draw_image(0, 10, 10, 0x0F) + b"\x00")),                   # 7  its length (1) before the lease
        step(msg(draw_string(t_off, 20, 40, 0x0F, b"AB"))),                 # 8  mode 14: 3
        step(msg(draw_string(t_off, 20, 40, 0x0F, b"AB")[:6])),             # 9  its header's length (1) before the lease
        step(msg(draw_string(t_off, 20, 40, 0x0F, b"AB") + b"A")),          # 10 the string's own length is asked after the lease: 3
        step({"lease": "acquire"}, msg(draw_string(t_off, 20, 40, 0x0F, b"AB"))),   # 11 a fresh lease, the cache went with the release: 5
        step(msg(bytes([10]))),                                             # 12 mode 10 with no argument: 1
        step(msg(bytes([10, 2]))),                                          # 13 neither stop nor start: 12
        step(msg(bytes([16, 3]))),                                          # 14 mode 16, an unknown sub: 12
        step(msg(bytes([16, 1]))),                                          # 15 a step with no message: 1 (the lease held, no begin)
        step(msg(bytes([16, 1]) + copy((0, 0, 8, 8), (8, 8, 8, 8)))),       # 16 a step with the lease held and no begin: 7
        step({"lease": "release"}, msg(bytes([16, 1]))),                    # 17 and before the lease is asked: 1
        step(msg(bytes([16, 1]) + copy((0, 0, 8, 8), (8, 8, 8, 8)))),       # 18 a step with no lease: 3
    ]})
    # Mode 15 draws with the firmware's own font: Damage never sends it and no offline model draws it.
    # These are only what it refuses BEFORE the font is read — the header's length (1), the lease (3),
    # the string's own length (1), in §4's order — so they are the same on the PC and on the glasses.
    # NEVER add a well-formed mode 15 with the lease held: the PC's host has no font and refuses it for a
    # reason the glasses do not have. A release inside: no self-test form.
    m15 = lambda text, declared=None: (bytes([15]) + u16(20) + u16(60)
                                       + bytes([0x0F, len(text) if declared is None else declared]) + text)
    v.append({"name": "v1-mode15", "steps": [
        step({"tick": 1000}, msg(kf), {"lease": "acquire"},
             msg(m15(b"AB", declared=5))),                                  # 0  the lease held, 2 bytes for a declared 5: 1
        step({"lease": "release"}, msg(m15(b"AB"))),                        # 1  well-formed, no lease: 3
        step(msg(m15(b"AB")[:5])),                                          # 2  shorter than its header, no lease: 1 before 3
    ]})
    # Mode 8's size limit at its exact edge (`Geometry.MODE8_MAX`, 153,718 B): two ~154 KB messages, the
    # costliest vector in the set (the fork's host reads a message's hex in one pass since 2026-11-12).
    batch_max = 118 + 320 * 480
    bm_ok = bytes([8, 1]) + u16(len(sub)) + sub
    bm_ok += bytes(batch_max - len(bm_ok))                              # the bytes after the counted sub are not read (v1-shape step 21)
    bm_sub2 = copy((0, 0, 64, 64), (100, 300, 64, 64))
    bm_big = bytes([8, 1]) + u16(len(bm_sub2)) + bm_sub2
    bm_big += bytes(batch_max + 1 - len(bm_big))
    v.append({"name": "v1-batchmax", "steps": [
        step({"tick": 1000}, msg(kf)),
        step(msg(bm_ok)),                                                   # 1  exactly the limit: accepted, its sub lands
        step(msg(bm_big)),                                                  # 2  one byte more: 1, its sub does not land
    ]})
    for vec in v:
        vec["contract"] = 1
        vec["start"] = "zero"
    return v + vectors_v2(kf)

def vectors_v2(kf):
    """The v2 set (`FIRMWARE.md` §4): every op, its per-lens form, a refusal per reason."""
    v = []
    # The lease and the flags come BEFORE the keyframe, as a session does it: a fresh acquire is a
    # release point, so arming after the keyframe left the panel stale and the NEXT present went
    # whole whatever hint it carried — which is why `v2-hint` took the partial path on none of its
    # steps (2026-09-15, the third review).
    arm = [{"tick": 1000}, {"lease": "acquire"}, flags(DRAW2), msg(kf)]

    # a cache: two images, a 224-entry font, a v1 record for the v1 window checks
    c = Cache2()
    icon = c.add(image2(40, 30, pattern(40, 30, 21)))
    tall = c.add(image2(24, 100, pattern(24, 100, 22)))
    glyphs = {ch: cache_image(5 + (ch % 7), 14, pattern(5 + (ch % 7), 14, 200 + ch)) for ch in range(32, 256)}
    table4 = c.reserve(224 * 2)
    goff = {ch: c.add(glyphs[ch]) for ch in range(32, 256)}
    c.put(table4, b"".join(u16(goff[ch]) for ch in range(32, 256)))
    bad_table4 = c.reserve(224 * 2)                      # every entry points at off4 0, written below as a zero record
    c.put(bad_table4, bytes(224 * 2))                    # written, not assumed: on glass the cache holds what was there
    c.put(0, bytes(8))                                   # off4 0 = a record of w 0: every bad entry is refused (5)
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

    # `HANDOFF.md` §73: a face packed with only the glyphs it draws, gaining more IN PLACE — a new
    # record appended at the cache's end and the table's entry rewritten where it is, in ONE message
    # (the phone's atlas does exactly this since §73; before it, every production cache write was a
    # single entry at a fresh offset). Every entry the face does not pack names one shared tofu record.
    s = Cache2()
    tofu = s.add(cache_image(7, 14, [15 if (x in (0, 6) or y in (0, 13)) else 0 for y in range(14) for x in range(7)]))
    sg = {ch: cache_image(5 + (ch % 7), 14, pattern(5 + (ch % 7), 14, 300 + ch)) for ch in b"ABCD\xff"}
    stable4 = s.reserve(224 * 2)
    soff = {ch: s.add(sg[ch]) for ch in b"AB"}
    s.put(stable4, b"".join(u16(soff.get(ch, tofu)) for ch in range(32, 256)))
    swrites = [msg(m) for m in s.messages()]
    def entry_unit(ch, value4, other4):
        """The 4-byte unit of the table that holds `ch`'s entry — an offset is in 4-byte units and an
        entry is two bytes, so the unit's OTHER entry rides along (`other4`: what it already holds)."""
        i = ch - 32
        lo, hi = (value4, other4) if i % 2 == 0 else (other4, value4)
        return (stable4 + i // 2, u16(lo) + u16(hi))
    def padded(rec): return rec + bytes(-len(rec) % 4)
    c_off4 = (len(s.buf) + 3) // 4
    ff_off4 = c_off4 + len(padded(sg[ord("C")])) // 4
    v.append({"name": "v2-tablepatch", "steps": [
        step(*arm, *swrites, msg(fill(rect(0, 0, 640, 480), 2))),
        step(msg(string2(stable4, 10, 10, 0x0F, b"AB"))),                          # the two packed glyphs
        step(msg(string2(stable4, 10, 40, 0x0F, b"ABC"))),                         # C is not packed: its entry names the tofu, and the tofu is drawn (accepted — the phone never sends an unpacked code)
        step(msg(cache_write2((c_off4, padded(sg[ord("C")])), entry_unit(ord("C"), c_off4, soff[ord("B")])))),   # ONE message: C's record appended, and the unit holding B's and C's entries rewritten (C is the odd one)
        step(msg(string2(stable4, 10, 70, 0x0F, b"ABC"))),                         # C from its own record; A and B exactly as before
        step(msg(string2_pair(stable4, 20, 36, 100, 0x1F, b"CAB"))),               # and per lens
        step(msg(cache_write2(entry_unit(ord("D"), soff[ord("A")], tofu)))),       # nothing appended: D's entry (the even one; E's stays the tofu) names A's record, already in the cache
        step(msg(string2(stable4, 10, 130, 0x0F, b"DAD"))),
        step(msg(cache_write2((ff_off4, padded(sg[0xff])), entry_unit(0xff, ff_off4, tofu)))),   # the table's LAST entry (code 255), its record in the same message
        step(msg(string2(stable4, 10, 160, 0x0F, b"A\xffB"))),
        step(msg(cache_write2(entry_unit(ord("A"), tofu, tofu), (0xFFFF, bytes(8)))),             # a message whose last entry leaves the cache is refused WHOLE (5): its table patch is not applied...
             msg(string2(stable4, 10, 190, 0x0F, b"AB"))),                         # ...so A still draws its glyph
    ]})

    far = Cache2(start=80000)
    faroff = far.add(image2(40, 30, pattern(40, 30, 41)))
    v1rec = cache_image(20, 20, pattern(20, 20, 42))
    v.append({"name": "v2-cachesize", "steps": [
        step({"tick": 1000}, msg(kf), cachesize(128)),                             # no lease: status 3, nothing set
        step({"lease": "acquire"}, flags(DRAW2), cachesize(0)),                    # zero: status 5
        step(cachesize(257)),                                                      # over the budget on every build: status 5 (161 moved to v3-cache256, C1 decision A.1)
        step(cachesize(128)),                                                      # ok: status 0
        step(*[msg(m) for m in far.messages()]),                                   # a write at 80,000: inside the 128 KiB
        step(msg(draw2(faroff, 10, 10, 0x0F))),
        step(cachesize(64)),                                                       # allocated already: status 4
        step(msg(cache_write((65500, v1rec)))),                                    # a v1 write ending past the v1 window: refused (5)
        step(msg(cache_write((100, v1rec))), msg(draw_image(100, 60, 10, 0x0F))),  # the v1 window works as before
        step(msg(cache_write2((65500 // 4, v1rec))), msg(draw_image(65500, 10, 200, 0x0F))),   # a record at byte 65,500, written by mode 19 inside the 128 KiB: the v1 draw is bounded by the first 64 KiB, so it is refused (5)
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
        step(msg(bytes([120, 0, 0, 0]))),                                          # an unknown mode (one no build plans; 25 is C1's, decision A.1): 8, then the stock BMP path
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

    # `"panel": true` (`FIRMWARE.md` §9): a hint changes what the LENS SHOWS and nothing else, so
    # without the panel CRC every claim below was unobservable — narrowing the hint of step 1 left
    # every expectation byte for byte identical (2026-09-15, the third review).
    v.append({"name": "v2-hint", "panel": True, "steps": [
        step(*arm),
        step(msg(batch(hint(100, 139), fill(rect(0, 100, 640, 40), 8)))),          # the rows the fill touched
        step(msg(batch(fill(rect(0, 0, 640, 480), 2), hint(0, 479)))),             # a whole-panel hint (the order in the batch is free)
        step(msg(batch(hint(200, 100), fill(rect(0, 0, 8, 8), 1)))),               # y0 > y1: refused (2)
        step(msg(batch(hint(0, 480), fill(rect(0, 0, 8, 8), 1)))),                 # y1 past the panel: refused (2)
        # the two refusals above changed the shadow and presented nothing, so the panel is stale:
        # one whole frame first, or "a later hint wins" would be proven against a full refresh
        step(msg(batch(fill(rect(0, 0, 640, 480), 3)))),                           # no hint: the panel is whole again
        step(msg(batch(hint(10, 20), hint(30, 40), fill(rect(0, 0, 640, 480), 5)))),   # the later hint wins: rows 30..40 only
    ]})
    # the Phase 2 review (2026-09-15): the check orders, the per-lens pair rule, and behaviours §4 states
    # that no vector pinned — `v2-edges` keeps a self-test form (no release, no control op mid-run)
    edge = Cache2()
    dot = edge.add(image2(2, 1, [1, 0]))                                          # source 1, then source 0
    w641 = edge.add(u16(641) + u16(1) + rle([5] * 641))                            # a record one column too wide
    h2049 = edge.add(u16(1) + u16(2049) + rle([5] * 2049))                         # and one row too tall
    unwritten = edge.reserve(8)                                                   # a record the refused write below never fills
    edge.put(unwritten, bytes(8))                                                 # written as zeros: refused (5) whatever was there before
    inv = [15 - i for i in range(16)]
    v.append({"name": "v2-edges", "steps": [
        step(*arm, *[msg(m) for m in edge.messages()]),
        step(msg(fill(rect(0, 0, 640, 480), 15)), msg(draw2(dot, 10, 10, 0x14))),     # transparency tests the SOURCE: 1 lands as 1*4/15 = 0, 0 is skipped
        step(msg(draw2(w641, 0, 0, 0x0F))),                                       # w 641: refused (5)
        step(msg(draw2(h2049, 0, 0, 0x0F))),                                      # h 2049: refused (5)
        step(msg(cache_write2((unwritten, image2(2, 2, [7, 7, 7, 7])), (0xFFFF, bytes(8)))),   # the second entry past the cache: refused (5) whole
             msg(draw2(unwritten, 20, 10, 0x0F))),                                # so the first was never written: refused (5)
        step(msg(fill_pair(rect(0, 0, 10, 10), rect(635, 0, 10, 10), 7))),        # the right rect past the panel: refused (2) on BOTH lenses
        step(msg(fill_pair(rect(0, 0, 10, 10), rect(0, 0, 20, 10), 7))),          # two sizes: refused (2) on both
        step(msg(batch(clip_pair(rect(0, 0, 100, 100), rect(600, 0, 100, 100)), fill(rect(0, 0, 640, 480), 3)))),   # a clip pair past the panel: refused (2)
        step(msg(lut_pair(rect(0, 0, 10, 10), rect(0, 0, 11, 10), inv))),         # two sizes: refused (2)
        step(msg(capture_pair(0, rect(0, 0, 512, 192), rect(8, 0, 512, 192)))),  # exactly the 49,152 B pool: taken
        step(msg(capture(1, rect(0, 0, 1, 1)))),                                  # one byte more: refused (7)
        step(msg(capture(0, rect(0, 0, 640, 480))), msg(fill(rect(0, 0, 640, 480), 1)), msg(restore(0))),   # a refused capture into a used slot keeps it
        step(msg(fill(rect(0, 0, 640, 480), 2)), msg(batch(clip(rect(0, 0, 10, 10)), restore(0)))),   # a restore ignores the clip
        step(msg(batch(clip(rect(0, 0, 10, 10)), copy((0, 0, 100, 100), (200, 200, 100, 100))))),     # and so does a v1 copy
        step(msg(clip(rect(0, 0, 64, 64)) + b"\x00")),                          # a clip the wrong length at the top level: length (1) before batch (10)
        step(msg(batch(bytes([0x98]) + u16(0) + u16(479), fill(rect(8, 0, 8, 8), 6)))),   # the high bit on a mode with no per-lens form: ignored (inside both captured rects, so the restore below shows on both lenses)
        step(msg(bytes([0x97, 1, 0]))),                                           # a restore under the high bit: ignored, restores
        step(msg(bytes([0x93]) + u16(unwritten) + u16(5) + image2(2, 1, [3, 3])), msg(draw2(unwritten, 30, 30, 0x0F))),   # 0x93: a mode-19 write, drawn
    ]})
    far2 = Cache2(start=80000)
    far2off = far2.add(image2(8, 8, [9] * 64))
    v.append({"name": "v2-lifecycle", "steps": [
        step({"tick": 1000}, msg(kf), msg(bytes([16]))),                          # a mode-16 message with no sub-op: refused (1), recorded
        step({"lease": "acquire"}, cachesize(63)),                                # below 64 KiB: status 5
        step(cachesize(64)),                                                      # 64 KiB: status 0
        step(msg(fill(rect(600, 0, 41, 10), 5))),                                 # past the panel, DRAW2 unarmed: DRAW2 (9) before bounds (2)
        step(*[msg(m) for m in far2.messages()]),                                 # a write past the cache, DRAW2 unarmed: 9 before record (5)
        step({"lease": "release"}, msg(cache_write2((4, bytes(4))))),              # no lease: 3
        step({"lease": "acquire"}, msg(bytes([19]))),                             # an empty list, DRAW2 unarmed: 9
        step(flags(0x0002)),                                                      # CACHE_KEEP armed: status 0
        step(cachesize(128)),                                                     # 128 KiB asked: status 0
        step({"lease": "release"}, {"lease": "acquire"}, flags(DRAW2),
             *[msg(m) for m in far2.messages()]),                                 # CACHE_KEEP latched, no cache up: the 128 KiB asked went with the release — 64 KiB, refused (5)
        step(msg(capture(0, rect(0, 0, 100, 100))), {"lease": "release"}, {"lease": "acquire"}, flags(DRAW2), msg(restore(0))),   # the release freed the slot: 7
        step(msg(capture(1, rect(0, 0, 100, 100))), msg(bytes([11])), {"lease": "acquire"}, flags(DRAW2), msg(restore(1))),     # so does mode 11
        step(msg(fill(rect(0, 0, 8, 8), 16)), msg(bytes([7, 0]))),                # a refusal, then mode 7 sub 0 clears the record
    ]})
    # ---- 2026-09-16 review: the two gates §4 opens with, on every op that has them -----------
    # A mutation sweep over the fork's C found the lease check (3) and the DRAW2 check (9) DEAD
    # for six of the eight v2 modes: only 19 and 21 were pinned, so a build that dropped either
    # for mode 17 — the image draw, the most-used v2 op — would have shipped with every gate
    # green. §4's first sentence is "needs the lease, needs flag bit 2 DRAW2 armed", and the
    # ORDER is part of the contract (3 before 9, both after length), so each op is asked twice.
    # Modes 20 and 24 are batch-only, so theirs are asked inside a batch; mode 24's length check
    # comes before the batch check, which is why it is sent well-formed.
    def no_lease_then_unarmed(m, label):
        return [step({"lease": "release"}, msg(m)),        # no lease: 3, whatever the flags were
                step({"lease": "acquire"}, msg(m))]        # lease back, DRAW2 not re-armed: 9
    lease_steps = [step({"tick": 1000}, {"lease": "acquire"}, flags(DRAW2), msg(kf)),
                   step(*[msg(m) for m in c.messages()])]
    for m, label in ((draw2(icon, 10, 10, 0x0F), "17 image draw"),
                     (string2(table4, 10, 40, 0x0F, b"ok"), "18 string draw"),
                     (cache_write2((4, bytes(4))), "19 cache write"),
                     (batch(clip(rect(0, 0, 100, 100)), fill(rect(0, 0, 8, 8), 5)), "20 clip in a batch"),
                     (fill(rect(0, 0, 8, 8), 5), "21 fill"),
                     (lut(rect(0, 0, 8, 8), list(range(16))), "22 LUT"),
                     (capture(0, rect(0, 0, 8, 8)), "23 save-under capture"),
                     (batch(hint(0, 9), fill(rect(0, 0, 8, 8), 5)), "24 present hint in a batch")):
        lease_steps += no_lease_then_unarmed(m, label)
    v.append({"name": "v2-gates", "steps": lease_steps})
    # ---- the second Phase 2 review (2026-09-15) --------------------------------------------
    # What the LENS shows, not just the shadow: a partial refresh (mode 24) adds its own rows and
    # leaves the rest of the panel as it was, so a hint that misses a row the batch changed is a
    # stale row on glass. `"panel": true` makes every step's expectation carry the panel's CRC too
    # (`FIRMWARE.md` §9), so the C and the simulator are compared on it. No release and no overlay
    # here: both would put content on the panel that neither side models byte for byte.
    pc = Cache2()
    pic = pc.add(image2(40, 30, pattern(40, 30, 61)))
    pglyphs = {ch: cache_image(5 + (ch % 7), 14, pattern(5 + (ch % 7), 14, 300 + ch)) for ch in range(32, 256)}
    ptable = pc.reserve(224 * 2)
    pgoff = {ch: pc.add(pglyphs[ch]) for ch in range(32, 256)}
    pc.put(ptable, b"".join(u16(pgoff[ch]) for ch in range(32, 256)))
    bad_draw = draw2(0xFFFF, 0, 0, 0x0F)                                           # a record past any cache: refused (5)
    v.append({"name": "v2-panel", "panel": True, "steps": [
        step(*arm, *[msg(m) for m in pc.messages()]),                              # the keyframe's full refresh: panel = shadow
        step(msg(batch(hint(100, 139), fill(rect(0, 100, 640, 40), 8)))),          # the hint covers the fill: panel = shadow
        step(msg(batch(hint(100, 109), fill(rect(0, 100, 640, 10), 5), fill(rect(0, 300, 640, 10), 9)))),   # a SHORT hint: rows 300..309 change in the shadow and not on the panel
        step(msg(batch(fill(rect(0, 420, 640, 20), 6)))),                          # no hint: the full refresh brings the panel back
        step(msg(batch(fill(rect(0, 100, 640, 10), 2), bad_draw)),                 # refused part-way: changed, presented nothing
             msg(batch(hint(200, 209), fill(rect(0, 200, 640, 10), 3)))),          # so this frame goes whole, not its rows
        step(msg(batch(hint(479, 479), fill(rect(0, 479, 640, 1), 15)))),          # the last row, inclusive
        step(msg(batch(hint(0, 0), fill(rect(0, 0, 640, 1), 1)))),                 # the first row
        step(msg(batch(hint(0, 479), fill_pair(rect(0, 60, 320, 40), rect(320, 60, 320, 40), 11),
                       draw2_pair(pic, 100, 116, 200, 0x1F),
                       string2_pair(ptable, 40, 56, 260, 0x0F, b"Panel" + bytes([13]) + b"2"),
                       delta_stereo(96, 104, 340, 120, 40, 1, pattern(120, 40, 62))))),   # a phone-shaped flush: fills, per-lens draws, a stereo delta, one hint
        step(msg(batch(delta_stereo(96, 104, 340, 120, 40, 1, pattern(120, 40, 63)),      # fid 1 again: skipped, the batch goes on
                       fill(rect(0, 460, 640, 10), 4), hint(340, 469)))),
        step(msg(batch(hint(100, 109), capture(0, rect(0, 100, 640, 10)), fill(rect(0, 100, 640, 10), 13)))),   # a capture presents nothing of its own
        step(msg(batch(hint(100, 109), restore(0)))),                              # the restore's rows are the hinted ones
        # ...and the third stale condition, which nothing compared until now (2026-09-16 review):
        # the stock compositor repaints, so the panel is no longer the previous Damage frame and
        # the next hinted flush must go WHOLE. `stockRepaint` had no caller on the Kotlin side at
        # all, so the C and the model had never been compared here.
        # while the lease is held the copy hook preserves the direct frame, so this changes nothing
        step(stock()),
        step(msg(batch(hint(300, 319), fill(rect(0, 300, 640, 20), 9)))),          # still the rows path
        # ...but once the lease is gone the stock compositor's content reaches the framebuffer and
        # the panel is no longer the previous Damage frame: the next hinted flush must go WHOLE
        step({"lease": "release"}, stock()),
        step({"lease": "acquire"}, flags(DRAW2),
             msg(batch(hint(340, 359), fill(rect(0, 340, 640, 20), 10)))),         # the hint is ignored: a full refresh
        step(msg(batch(hint(380, 399), fill(rect(0, 380, 640, 20), 11)))),         # and the panel is whole again: the rows path
    ]})

    # Records and font tables ABOVE the v1 window in a 160 KiB cache — what the phone's own atlas
    # does with nine faces. A build that bounded v2 records by the first 64 KiB, as the v1 modes
    # are, would refuse every one of these while the model drew them.
    hi = Cache2(start=150000)
    hipic = hi.add(image2(40, 30, pattern(40, 30, 71)))
    higlyphs = {ch: cache_image(4 + (ch % 5), 12, pattern(4 + (ch % 5), 12, 400 + ch)) for ch in range(32, 256)}
    hitable = hi.reserve(224 * 2)
    higoff = {ch: hi.add(higlyphs[ch]) for ch in range(32, 256)}
    hi.put(hitable, b"".join(u16(higoff[ch]) for ch in range(32, 256)))
    # A TALL record for the page-turn step: 300 x 900 in 8-row bands, so it is a handful of RLE
    # runs and the scroll offset is visible in the bands the clip shows (2026-09-15, the third
    # review: the step used the 40x30 icon at y = -20 under a clip starting at row 40, so the two
    # never met and it drew nothing)
    hitall = hi.add(image2(300, 900, [((y // 8) % 15) + 1 for y in range(900) for _ in range(300)]))
    assert len(hi.buf) < 160 * 1024, len(hi.buf)
    edge_end = Cache2(start=163840 - 12)                                           # a record ending exactly at the cache's end
    last = edge_end.add(image2(8, 8, [9] * 64))
    v.append({"name": "v2-reach", "steps": [
        step(*arm, cachesize(160), *[msg(m) for m in hi.messages()]),
        step(msg(draw2(hipic, 10, 10, 0x0F))),                                     # a record above 64 KiB draws
        step(msg(string2(hitable, 10, 60, 0x0F, bytes(range(32, 128))))),          # a table above 64 KiB, the ASCII half
        step(msg(string2_pair(hitable, 10, 26, 90, 0x1F, bytes(range(160, 256))))), # and the Latin-1 half, per lens
        step(*[msg(m) for m in edge_end.messages()], msg(draw2(last, 20, 300, 0x0F))),   # a record ending exactly at 160 KiB
        step(msg(cache_write2((163840 // 4 - 1, bytes(8))))),                      # an entry running one byte past the end: refused (5)
        step(msg(batch(clip_pair(rect(0, 100, 60, 40), rect(8, 100, 60, 40)),
                       draw2_pair(hipic, -12, -4, 90, 0x1F),
                       string2_pair(hitable, -3, 5, 110, 0x0F, b"Ag")))),          # a clip and negative per-lens x together
        step(msg(batch(clip(rect(0, 40, 640, 400)), draw2(hitall, 0, -60, 0x0F)))),   # a TALL record scrolled up under a clip (Reader's page turn):
                                                                                      # rows 60..459 of it land in the clip, the 60 above it are dropped
    ]})

    # Check orders and edges one message at a time: what the phone never sends, but a defect would.
    v.append({"name": "v2-order", "steps": [
        step(*arm, *writes),
        step(msg(fill(rect(600, 0, 41, 10), 16))),                                 # a bad rect AND a bad level: bounds (2) first
        step(msg(bytes([18]) + u16(0) + s16(0) + s16(0) + bytes([0x0F, 2]) + b"A")),   # mode 18 one byte short: length (1)
        step(msg(bytes([23, 3, 7]))),                                              # sub 3 with slot 7: the sub (12) before the slot (7)
        step(msg(capture(5, rect(0, 0, 0, 0)))),                                   # slot 5 with an empty rect: the slot (7) before the rect (2)
        step(msg(capture(0, rect(1, 3, 333, 77))), msg(fill(rect(0, 0, 640, 480), 9)), msg(restore(0))),   # an odd left edge through the save-under path
        step(msg(lut(rect(1, 1, 333, 77), inv))),                                  # an odd left edge through the LUT
        step(msg(batch(clip(rect(0, 0, 10, 10)), draw_image(0, 100, 100, 0x0F), draw_string(0, 100, 130, 0x0F, b"v1")))),   # the v1 draws ignore the clip
        step(msg(bytes([19]))),                                                    # an empty list with DRAW2 armed: accepted, writes nothing
        step(msg(cache_write2((0xFFFF, b"")))),                                    # an empty entry past the cache: refused (5)
        step(msg(bytes([3, 0, 0, 4, 4]))),                                         # a mode-3 message with no fid and no stream: length (1)
        step(msg(bytes([6]))),                                                     # a mode-6 message with no stream: length (1), before the fid ring
        step(msg(delta(8, 8, 16, 8, 9, pattern(16, 8, 81)))),                      # fid 9 after them: not a skip if the ring stayed clean
    ]})
    # records whose RLE does not decode to their size: the draw is refused (5) and nothing of the
    # record reaches the shadow. The zero-length run is the one that must also TERMINATE — it
    # advances the stream without filling a pixel, so the validation ends when the bytes run out.
    # Every record here is written PADDED to its 4-byte boundary with explicit zeros. A v2 record has no
    # length field — the decoder reads tokens until the pixel count is met — so a record that is short
    # reads on into the bytes after it. The host and the simulator start from a zeroed cache, but the
    # glasses' live cache holds the shell's atlas at these offsets: on glass (2026-09-16, the first
    # self-test of Phase 2, `HANDOFF.md` §65) `short` read the atlas's bytes and DREW where both models
    # refused. The vector's claim must not depend on what a cache held before it.
    pad4 = lambda b: b + bytes((-len(b)) % 4)
    bad = Cache2()
    over = bad.add(pad4(u16(4) + u16(1) + bytes([0x35, 0x25])))                    # 3 + 2 pixels for a 4-pixel record
    short = bad.add(pad4(u16(8) + u16(1) + bytes([0x35])))                         # 3 pixels for an 8-pixel record
    zero = bad.add(pad4(u16(4) + u16(1) + bytes([0x05, 0x00, 0x00, 0x00]) + bytes([0x35])))   # a zero-length 16-bit run, then 3
    # A valid glyph and a table in the SAME cache, so the last step measures what it claims: a draw
    # after three refused records still lands. (Until 2026-09-16 it drew through `table4`, which this
    # cache never wrote, and was refused with reason 5 under a comment saying the opposite — §64.5.)
    glyph = bad.add(pad4(cache_image(4, 14, pattern(4, 14, 23))))
    tbl = bad.reserve(224 * 2)
    bad.put(tbl, b"".join(u16(glyph) for _ in range(224)))
    v.append({"name": "v2-badrec", "steps": [
        step(*arm, *[msg(m) for m in bad.messages()], msg(fill(rect(0, 0, 640, 480), 4))),
        step(msg(draw2(over, 10, 10, 0x0F))),                                      # more pixels than the record holds: 5
        step(msg(draw2(short, 10, 20, 0x0F))),                                     # fewer: the stream ends first: 5
        step(msg(draw2(zero, 10, 30, 0x0F))),                                      # a run of zero length: 5, and it ends
        step(msg(string2(tbl, 10, 40, 0x0F, b"ok"))),                              # a valid draw from the same cache still lands
    ]})

    # ---- the vector pass (2026-11-12, APK 0.65): the v2 twins of `v1-shape` --------------------------
    # Every v2 op's length (`FIRMWARE.md` §4's layouts), "checked whole" for modes 18 and 19, and mode
    # 23's capture rect — a sweep operator that removed the rect read with its check called it caught;
    # with the read kept and only the refusal dropped no gate reacted. Lease held: a self-test form.
    sc = Cache2()
    sv_pic = sc.add(image2(40, 30, pattern(40, 30, 91)))
    sv_pic2 = image2(40, 30, pattern(40, 30, 92))                                  # same size, other pixels
    zrec = sc.add(bytes(4))                                                        # a record of w 0, WRITTEN
    sv_g2 = {ch: cache_image(5 + (ch % 7), 14, pattern(5 + (ch % 7), 14, 600 + ch)) for ch in b"AB"}
    stab = sc.reserve(224 * 2)
    sv_g2off = {ch: sc.add(sv_g2[ch]) for ch in b"AB"}
    sc.put(stab, b"".join(u16(sv_g2off.get(ch, zrec)) for ch in range(32, 256)))
    inv = [15 - i for i in range(16)]
    s2 = string2(stab, 10, 200, 0x0F, b"AB")
    v.append({"name": "v2-shape", "steps": [
        step(*arm, *[msg(m) for m in sc.messages()], msg(fill(rect(0, 0, 640, 480), 2))),
        # mode 21: 10 bytes, 18 under the high bit
        step(msg(fill(rect(0, 0, 64, 64), 9) + b"\x00")),                   # 1  one byte long: 1
        step(msg(fill(rect(0, 0, 64, 64), 9)[:-1])),                        # 2  no level: 1
        step(msg(bytes([0x95]) + rect(0, 0, 64, 64) + bytes([9]))),         # 3  one rect under the high bit: 1
        # mode 22: 17 bytes, 25 under the high bit
        step(msg(lut(rect(0, 0, 64, 64), inv) + b"\x00")),                  # 4  one byte long: 1
        step(msg(bytes([0x96]) + rect(0, 0, 64, 64) + lut_bytes(inv))),     # 5  one rect under the high bit: 1
        # mode 23
        step(msg(capture(0, rect(100, 100, 60, 40))), msg(fill(rect(0, 0, 640, 480), 7))),   # 6
        step(msg(restore(0) + b"\x00")),                                    # 7  a restore one byte long: 1, nothing restored
        step(msg(bytes([23]))),                                             # 8  no sub: 1
        step(msg(bytes([23, 1]))),                                          # 9  no slot: 1
        step(msg(capture(1, rect(0, 0, 8, 8))[:-1])),                       # 10 a capture one byte short: 1
        step(msg(bytes([0x97, 0, 1]) + rect(0, 0, 8, 8))),                  # 11 one rect under the high bit: 1
        step(msg(capture(1, rect(600, 0, 41, 10)))),                        # 12 a capture past the panel: 2
        step(msg(capture(1, rect(0, 0, 0, 10)))),                           # 13 an empty rect: 2
        step(msg(capture_pair(1, rect(0, 0, 10, 10), rect(0, 0, 20, 10)))), # 14 a pair of two sizes: 2 on both lenses
        step(msg(free_slot(0) + b"\x00")),                                  # 15 a free one byte long: 1
        step(msg(restore(0))),                                              # 16 so slot 0 is still held: restores
        step(msg(restore(1))),                                              # 17 and slot 1 never took a capture: 7
        # mode 24
        step(msg(hint(0, 9) + b"\x00")),                                    # 18 top level, one byte long: length (1) before batch (10)
        step(msg(batch(hint(0, 9) + b"\x00", fill(rect(0, 0, 640, 10), 5)))),   # 19 in a batch: 1, the fill after it does not land
        step(msg(batch(hint(0, 9)[:-1], fill(rect(0, 0, 640, 10), 5)))),    # 20 one byte short: 1
        # mode 18
        step(msg(s2[:5])),                                                  # 21 shorter than its header: 1
        step(msg(string2_pair(stab, 10, 26, 200, 0x0F, b"AB")[:10])),       # 22 the per-lens header one byte short: 1
        step(msg(s2 + b"A")),                                               # 23 a byte after the declared string: 1
        step(msg(string2(stab, 10, 200, 0x0F, b"AZ"))),                     # 24 Z names the zero record: 5, and A is not drawn
        step(msg(s2)),                                                      # 25 whole: lands
        # mode 19: the whole list is checked before a byte is written
        step(msg(cache_write2((sv_pic, sv_pic2)) + b"\x00\x00\x00"),        # 26 a sound entry, then 3 bytes of a header: 1 ...
             msg(draw2(sv_pic, 300, 100, 0x0F))),                           #    ... the sound entry was not written: the first picture draws
        step(msg(cache_write2((sv_pic, sv_pic2)) + u16(2000) + u16(100) + bytes(4)),   # 27 a sound entry, then data running past the message: 1
             msg(draw2(sv_pic, 360, 100, 0x0F))),
        step(msg(cache_write2((sv_pic, sv_pic2))), msg(draw2(sv_pic, 420, 100, 0x0F))),   # 28 the entry alone: written, the second picture draws
    ]})
    # Mode 14's table must lie in the first 64 KiB whatever the cache's size (§4: "the v1 window
    # addressed by modes 12/13/14 stays the first 64 KiB"). `v2-cachesize` asks it of a mode-13 record;
    # this asks it of the mode-14 table, at the exact edge, with every byte past the window written.
    win_ga = 0x0808                            # a glyph offset whose two bytes are equal: a table of it reads the same at any alignment
    win_glyph = cache_image(9, 12, pattern(9, 12, 700))
    win_edge = 65536 - 96 * 2                  # 65,344: the last table offset the window admits
    v.append({"name": "v2-window", "steps": [
        step(*arm, cachesize(128),
             msg(cache_write((win_ga, win_glyph), (win_edge, bytes([8]) * 192))),   # the glyph, and a table ending exactly at the window's end
             msg(cache_write2((65536 // 4, bytes([8]) * 4)))),              # the same bytes carried on past the window, inside the 128 KiB
        step(msg(draw_string(win_edge, 20, 20, 0x0F, b"AB"))),              # 1  the last offset: draws
        step(msg(draw_string(win_edge + 1, 20, 40, 0x0F, b"AB"))),          # 2  one byte later: 5 — every entry it would read is in the cache and names a sound record
    ]})

    for vec in v:
        vec["contract"] = 2
        vec["start"] = "zero"
    return v

# ---- C1: motion programs and the riders (`FIRMWARE.md` §5, build C1's decisions) -------------------------
# Written from §5.4 (records), §5.5 (mode 26), §5.9 (mode 25, op 5) and §5.10 (the vector form) alone. The
# vector form adds (C1 decisions A.3): the op {"wake": true} — one engine wake at the clock the last `tick`
# op set — and a vector-level "features" key, the DamageCaps features the build under test answers. The
# expectations it adds ("N" notify bodies, "W" planned waits, "G" field 17 per lens) are the C's to fill.
ENGINE, ZWRITE = 0x0008, 0x0020            # §5.8: flag bits 3 and 5
FEATURES_C1 = 0x1FF                        # §5.1: today's 0x7f plus bit 7 ENGINE and bit 8 the riders (bit 9 not in C1)
WAIT, FILL, DRAW, REVEAL = 0, 1, 2, 3      # §5.4 segment verbs
LINEAR, EASE_OUT, TABLE = 0, 1, 2          # §5.4 eases
K_RECORD, K_SELF, K_LEVEL = 0, 2, 3        # §5.4 DRAW / REVEAL source kinds
BD_SELF, BD_NONE = 0x20, 0xFF              # §5.4 backdrops (0..15 a level)
(F_SRC, F_XL, F_XR, F_X, F_Y, F_DXL, F_DXR, F_DX, F_DY,
 F_LVL0, F_LVL1, F_H0, F_H1, F_STEPS) = range(14)   # §5.4 pref fields
TOP, BOTTOM, LEFT, RIGHT, SLIDE = 0, 1, 2, 3, 4      # §5.4 REVEAL opt: bits 0-1 the edge, bit 2 slide
WELL_FORMED, BAD_SHAPE = set(), set()      # the self-check's registry: programs that must walk; messages built malformed on purpose

def seg(verb, steps, ease=LINEAR, kind=0, src=0, xL=0, xR=0, y=0, dxL=0, dxR=0, dy=0, lvl0=0, lvl1=0,
        opt=0, table=0, h0=0, h1=0, anchor=0, pref0=0, pref1=0, res0=0, res1=0):
    """One 32-byte segment (§5.4): verb steps ease kind · src u16 · xL xR y dxL dxR dy s16 · lvl0 lvl1 opt 0 ·
    table u16 · h0 h1 u16 · anchor 0 · pref0 pref1. Unused fields stay 0 (`res0`/`res1` are the two reserved bytes)."""
    b = (bytes([verb, steps, ease, kind]) + u16(src) + s16(xL) + s16(xR) + s16(y) + s16(dxL) + s16(dxR) + s16(dy)
         + bytes([lvl0, lvl1, opt, res0]) + u16(table) + u16(h0) + u16(h1) + bytes([anchor, res1, pref0, pref1]))
    assert len(b) == 32
    return b
def wait(n): return seg(WAIT, n)
def fillseg(n, level): return seg(FILL, n, kind=level)
def drawrec(n, src, x, y, dx=0, dy=0, lvl=(15, 15), opt=0, ease=LINEAR, xR=None, dxR=None, **kw):
    """DRAW of a cached v2 image record; give xR/dxR only on a per-lens track (a non-per-lens track uses xL/dxL)."""
    return seg(DRAW, n, ease, K_RECORD, src, x, xR or 0, y, dx, dxR or 0, dy, lvl[0], lvl[1], opt, **kw)
def drawself(n, x, y, dx=0, dy=0, lvl=(15, 15), opt=0, ease=LINEAR, xR=None, dxR=None, **kw):
    """DRAW of the track's own capture (`self`); src is ignored (decision A.9) and sent 0."""
    return seg(DRAW, n, ease, K_SELF, 0, x, xR or 0, y, dx, dxR or 0, dy, lvl[0], lvl[1], opt, **kw)
def reveal(n, edge, kind, x, y, src=0, w=0, h=0, xR=None, ease=LINEAR, lvl=(15, 15), **kw):
    """REVEAL (wipe, or slide with SLIDE in `edge`): a record (src = off4), `self`, or a level source
    (src = the level, w = its width carried in dxL, h = its height carried in dy)."""
    return seg(REVEAL, n, ease, kind, src, x, xR or 0, y, w, 0, h, lvl[0], lvl[1], edge, **kw)
def pref(param, field): return 0x80 | (field << 2) | param          # §5.4: bit 7 used, bits 0-1 P, bits 2-6 the field

def track(area, backdrop, *segs, per_lens=None, nseg=None, res=0):
    """A track: segments backdrop perLens 0 · a rect, or a pair given as (rectL, rectR) · its segments."""
    pair = isinstance(area, tuple)
    pl = (1 if pair else 0) if per_lens is None else per_lens
    return bytes([len(segs) if nseg is None else nseg, backdrop, pl, res]) + (area[0] + area[1] if pair else area) + b"".join(segs)

def prog(*tracks, version=1, ntracks=None, length=None, tail=b"", bad=False, reg=True):
    """A program record (§5.4): version 1 · tracks · length u16 (the whole record), then the tracks. `reg` False: bytes
    no message is expected to name (v5-budget's trial programs)."""
    body = b"".join(tracks) + tail
    rec = bytes([version, len(tracks) if ntracks is None else ntracks]) + u16(4 + len(body) if length is None else length) + body
    if reg: (BAD_SHAPE if bad else WELL_FORMED).add(rec)
    return rec

def easing(E, n=None):
    """An easing table (§5.4): n u8 · 0 · E[1..n] s16."""
    return bytes([len(E) if n is None else n, 0]) + b"".join(s16(e) for e in E)

def cap_total(*wh):
    """The capture range's length for the `self` tracks' areas (w, h) in track order (decision A.5): each capture
    (w+1)/2 bytes a row, each starting 4-byte aligned; the range ends at the end of the last."""
    off = 0
    for i, (w, h) in enumerate(wh):
        if i: off = (off + 3) & ~3
        off += ((w + 1) // 2) * h
    return off

def _p4(P):
    P = tuple(P)
    assert len(P) <= 4
    return b"".join(s16(p) for p in P + (0,) * (4 - len(P)))
def play(slot, period, prog4, tag, cap4=0, P=()):                     # §5.5 sub 0: 16 B after [26][0]
    return bytes([26, 0, slot, period]) + u16(prog4) + u16(tag) + u16(cap4) + _p4(P)
def retarget(slot, P=()): return bytes([26, 1, slot]) + _p4(P)        # §5.5 sub 1: 9 B
def close(slot): return bytes([26, 2, slot])                          # §5.5 sub 2: 1 B (0xFF all)
def zwrite(*entries):
    """Mode 25 (§5.9): [25] + the zlib stream of a mode-19 entry list without its mode byte."""
    return bytes([25]) + z(cache_write2(*entries)[1:])
def bad(m):
    """A message built malformed on purpose: the self-check does not hold it to its builder's length."""
    BAD_SHAPE.add(bytes(m))
    return m
def wake(): return {"wake": True}
def tk(t): return {"tick": t}

def vectors_v3(kf):
    """The C1 set (`FIRMWARE.md` §5.10, spec §4): engine vectors carry "panel"; every engine message and every
    wake follows a `tick` op in its step (the hook reads that clock as now). Every vector answers features 0x1ff."""
    v = []
    P = 34                                                        # §5.3: A4's 30 Hz is P = 34
    arm = [tk(1000), {"lease": "acquire"}, flags(DRAW2 | ENGINE), msg(kf)]
    def writes(c): return [msg(m) for m in c.messages()]
    def vec(name, steps, panel=True):
        d = {"name": name, "steps": steps}
        if panel: d["panel"] = True
        v.append(d)

    # v3-play — §5.6 "END is written into the shadow when the PLAY is handled"; §5.3 "Step 1 is drawn by the
    # PLAY's own present", steps passed over are folded, step T always drawn; §5.3 "the present after an
    # instance's last step marks the panel stale" as decision A.12 rules it (the present that draws the last
    # step sets it, so the NEXT present goes whole, not the one after); §5.6 "a phone write inside a drawing
    # instance's area ... after a last step drawn by an engine refresh the framebuffer holds step T".
    c = Cache2()
    pic = c.add(image2(40, 30, pattern(40, 30, 301)))
    A, B = rect(100, 100, 240, 60), rect(110, 110, 200, 40)
    pp = c.add(prog(track(A, 2, fillseg(8, 5)),
                    track(B, BD_NONE, drawrec(8, pic, 110, 115, dx=150))))      # a `none` backdrop inside track 1's area
    inA = rect(150, 120, 40, 20)
    vec("v3-play", [
        step(*arm, *writes(c)),
        step(tk(2000), msg(play(0, P, pp, 0x0101))),                    # 1  END in the shadow; step 1 on the panel; W = P
        step(tk(2000 + P), wake()),                                     # 2  step 2
        step(tk(2000 + 2 * P), wake()),                                 # 3  step 3
        step(tk(2000 + 5 * P), wake()),                                 # 4  k = 6: steps 4 and 5 folded
        step(tk(2000 + 7 * P), wake()),                                 # 5  k = 8 = T: the last step, notify outcome 0; W forever
        step(tk(2300), msg(batch(fill(inA, 9), fill(rect(0, 400, 640, 10), 6), hint(400, 409)))),   # 6  stale: whole (the fill in the area reaches the lens)
        step(tk(2400), msg(batch(fill(inA, 10), fill(rect(0, 420, 640, 10), 7), hint(420, 429)))),  # 7  the mark is spent: rows only (level 10 stays off the lens)
        step(tk(3000), msg(play(0, P, pp, 0x0102))),                    # 8  a second PLAY: END over both fills, step 1
        step(tk(3000 + P), wake()),                                     # 9  step 2
        step(tk(3000 + 3 * P + 10), msg(fill(inA, 12))),                # 10 a write into the drawing area, no CLOSE: its present draws k = 4 over it
        step(tk(3000 + 7 * P), wake()),                                 # 11 the last step by a wake: the panel holds step T, not the write
        step(tk(3000 + 9 * P), wake()),                                 # 12 nothing drawing: nothing presents, the panel keeps step T
        step(tk(3400), msg(batch(fill(rect(0, 440, 640, 10), 4), hint(440, 449)))),   # 13 a phone frame elsewhere: whole (stale), the write reaches the lens
    ])

    # v3-draw — §5.4 DRAW: "the source at (x + off(dx), y + off(dy)) (per lens), options = (opt & 0x30) | lvl(j)",
    # "the v1 LUT ... applies to records and `self` alike", "clipped to the area"; `self` captured "per lens ...
    # into the cache at the PLAY's cap offset — the tracks' captures back to back in track order, each starting
    # 4-byte aligned"; §5.3 folds (a wake late by three steps).
    c = Cache2()
    pic = c.add(image2(40, 30, pattern(40, 30, 311)))
    T1 = (rect(100, 200, 160, 60), rect(116, 200, 160, 60))          # per lens, over different shadow content
    T3 = (rect(100, 300, 200, 50), rect(108, 300, 200, 50))
    T5 = (rect(10, 420, 21, 9), rect(14, 420, 21, 9))
    pd = c.add(prog(
        track(T1, BD_SELF, drawself(6, 100, 200, dx=40, dy=12, xR=116, dxR=56, lvl=(15, 6), opt=0x10, ease=EASE_OUT)),
        track(rect(320, 200, 200, 80), 1, drawrec(6, pic, 300, 210, dx=210, lvl=(3, 15), opt=0x20)),   # enters from left of the area, leaves past its right edge
        track(T3, 5, drawrec(4, pic, 100, 310, dx=100, dy=-5, xR=108, dxR=124, opt=0x30)),            # shorter than T: holds its end
        track(rect(401, 321, 33, 11), 0, drawself(6, 401, 321, dx=-6, dy=3)),                          # an odd-width capture
        track(T5, BD_SELF, drawself(3, 10, 420, dx=4, xR=14, dxR=4))))                                  # starts aligned after the odd one
    dcap = c.reserve(cap_total((160, 60), (33, 11), (21, 9)))
    vec("v3-draw", [
        step(*arm, *writes(c), msg(fill_pair(rect(100, 200, 40, 20), rect(150, 200, 40, 20), 15))),   # the lenses' areas now differ
        step(tk(2000), msg(play(0, P, pd, 0x0201, dcap))),              # 1  captures at cap per lens; END; step 1
        step(tk(2000 + 4 * P), wake()),                                 # 2  late by three steps: 2, 3 and 4 folded, step 5 drawn
        step(tk(2000 + 5 * P), wake()),                                 # 3  step 6 = T: the notify says 3 drawn, 3 folded
        step(tk(2300), msg(batch(fill(rect(0, 470, 8, 8), 1)))),        # 4  a phone frame: the panel shows the shadow whole
    ])

    # v3-reveal — §5.4 REVEAL: "extent e(j) eased from 0 to F (F = the source's height for edge 0 top / 1 bottom,
    # its width for 2 left / 3 right ...). Wipe: the source at rest (x, y), visible only in the e rows or columns
    # at that edge of its rect. Slide: the source moved in from the edge by F − e"; sources: a record, `self`, a
    # solid level `src` of width dxL and height dy; F = 1.
    c = Cache2()
    ra = c.add(image2(40, 30, pattern(40, 30, 321)))
    col1 = c.add(image2(1, 20, pattern(1, 20, 322)))                  # F = 1 on a left/right edge
    row1 = c.add(image2(30, 1, pattern(30, 1, 323)))                  # F = 1 on a top/bottom edge
    g = lambda i, j: rect(20 + 100 * i, 20 + 100 * j, 80, 60)
    r1 = c.add(prog(
        track(g(0, 0), 2, reveal(5, TOP, K_RECORD, 40, 35, src=ra)),
        track(g(1, 0), 4, reveal(5, BOTTOM, K_SELF, 120, 20)),
        track(g(2, 0), 0, reveal(5, LEFT, K_LEVEL, 230, 30, src=11, w=50, h=40)),
        track(g(3, 0), 6, reveal(5, RIGHT, K_RECORD, 340, 35, src=ra)),
        track(g(0, 1), 3, reveal(5, SLIDE | TOP, K_SELF, 20, 120)),
        track(g(1, 1), 1, reveal(5, SLIDE | BOTTOM, K_LEVEL, 130, 130, src=13, w=60, h=40)),
        track(g(2, 1), 5, reveal(5, SLIDE | LEFT, K_RECORD, 240, 135, src=ra)),
        track(g(3, 1), 7, reveal(5, SLIDE | RIGHT, K_SELF, 320, 120))))
    rcap = c.reserve(cap_total((80, 60), (80, 60), (80, 60)))
    r3 = c.add(prog(
        track(rect(20, 220, 80, 20), 2, reveal(4, TOP, K_LEVEL, 30, 225, src=15, w=40, h=1)),      # a level source one row high
        track(rect(120, 220, 80, 30), 3, reveal(4, SLIDE | LEFT, K_RECORD, 150, 225, src=col1)),  # a record one column wide
        track(rect(220, 220, 80, 30), 4, reveal(3, RIGHT, K_LEVEL, 250, 225, src=9, w=1, h=20)),  # a level source one column wide
        track(rect(320, 220, 80, 30), 1, reveal(4, SLIDE | BOTTOM, K_RECORD, 340, 230, src=row1))))
    vec("v3-reveal", [
        step(*arm, *writes(c)),
        step(tk(2000), msg(play(0, P, r1, 0x0301, rcap)), tk(2000), msg(play(1, P, r3, 0x0302))),   # 1  both at one tick
        step(tk(2000 + P), wake()),                                     # 2
        step(tk(2000 + 2 * P), wake()),                                 # 3
        step(tk(2000 + 3 * P), wake()),                                 # 4  slot 1's last step (T = 4)
        step(tk(2000 + 4 * P), wake()),                                 # 5  slot 0's last step (T = 5)
        step(tk(2300), msg(batch(fill(rect(0, 470, 8, 8), 1)))),        # 6  the shadow whole: END of both
    ])

    # v3-ease — §5.4: "E[0] = 0 always. Linear E[j] = ⌊4096·j / n⌋; ease-out E[j] = 4096 − ⌊4096·(n−j)² / n²⌋;
    # table: the record at `table` (off4), whose n must equal the segment's steps (else 5). An eased value:
    # v(j) = v0 + ((D · E[j] + 2048) >> 12) ... so v(n) = v0 + D exactly. Bounces and overshoot are uploaded
    # tables (E may leave 0..4096)"; a table's E[n] must be 4096 (decision A.9: else 5).
    c = Cache2()
    bar = c.add(image2(10, 20, pattern(10, 20, 331)))
    over = c.add(easing([800, 1800, 3000, 4300, 4700, 4400, 4000, 4096]))       # overshoot
    under = c.add(easing([-400, -200, 600, 2000, 3600, 4500, 4200, 4096]))      # undershoot first, then over
    notend = c.add(easing([1000, 2000, 3000, 4000, 4500, 4200, 4000, 4000]))    # E[n] = 4000: 5
    def eprog(n, ease, tab=0, ramp=True):
        lv = (2, 15) if ramp else (15, 15)
        return c.add(prog(
            track(rect(0, 40, 640, 24), 1, drawrec(n, bar, 4, 42, dx=600, ease=ease, table=tab)),
            track(rect(0, 70, 640, 24), 1, drawrec(n, bar, 610, 72, dx=-597, lvl=lv, ease=ease, table=tab)),   # a negative D: the arithmetic shift
            track(rect(0, 100, 640, 60), 1, drawrec(n, bar, 300, 100, dx=7, dy=37, lvl=(15, 15), ease=ease, table=tab))))
    eps = [(n, e, eprog(n, e)) for n in (1, 2, 7, 255) for e in (LINEAR, EASE_OUT)]
    p_over, p_under = eprog(8, TABLE, over, ramp=False), eprog(8, TABLE, under, ramp=False)
    p_notend = eprog(8, TABLE, notend, ramp=False)
    p_short = eprog(7, TABLE, over, ramp=False)                         # a table of 8 for a 7-step segment: 5
    st = [step(*arm, *writes(c))]
    t, tag = 2000, 0x0400
    def run(prog4, n, period, at):
        nonlocal t, tag
        tag += 1
        out = [step(tk(t), msg(play(0, period, prog4, tag)))]
        t0 = t
        for j in at:                                                    # a wake on the step each j is due
            out.append(step(tk(t0 + (j - 1) * period), wake()))
        t = t0 + n * period + 100
        return out
    for n, e, p4 in eps:
        at = range(2, n + 1) if n <= 8 else (2, 3, 128, 254, 255)
        st += run(p4, n, P if n <= 8 else 16, at)
    st += run(p_over, 8, P, range(2, 9))
    st += run(p_under, 8, P, range(2, 9))
    st += [step(tk(t), msg(play(0, P, p_notend, 0x04F0))),               # E[n] ≠ 4096: 5
           step(tk(t + 10), msg(play(0, P, p_short, 0x04F1)))]           # n ≠ steps: 5
    vec("v3-ease", st)

    # v3-params — §5.4 "Parameters: a PLAY carries P0..P3 (s16) ... 0 src · 1 xL · 2 xR · 3 xL and xR · 4 y ·
    # 5 dxL · 6 dxR · 7 dxL and dxR · 8 dy · 9 lvl0 · 10 lvl1 · 11 h0 · 12 h1 · 13 steps (u8 fields take the low
    # byte; every check runs after substitution)"; §5.5 "every step's values in range: levels 0..15, ... h0/h1 = 0,
    # T ≤ 255 (12)". Static values a check would refuse are substituted to good ones (the record past the cache,
    # level 16, steps 0), so "every check runs after substitution" is proven by acceptance.
    c = Cache2()
    pic = c.add(image2(40, 30, pattern(40, 30, 341)))
    pic2 = c.add(image2(24, 24, pattern(24, 24, 342)))
    pair = lambda l, t_, w, h, d=16: (rect(l, t_, w, h), rect(l + d, t_, w, h))
    pB = c.add(prog(
        track(rect(0, 20, 300, 80), 2, seg(DRAW, 6, kind=K_RECORD, src=0xFFFF, xL=10, y=0, dxL=200, lvl0=15, lvl1=15,
                                           pref0=pref(0, F_SRC), pref1=pref(1, F_Y))),
        track(rect(320, 20, 300, 80), 3, seg(DRAW, 6, kind=K_RECORD, src=pic, xL=330, y=30, dxL=100, lvl0=16, lvl1=15,
                                             pref0=pref(2, F_DY), pref1=pref(3, F_LVL0)))))
    pC = c.add(prog(
        track(pair(0, 120, 300, 60), 4, seg(DRAW, 6, kind=K_RECORD, src=pic, xL=-30000, xR=30000, y=130, dxL=80, dxR=80,
                                            lvl0=15, lvl1=15, pref0=pref(0, F_XL), pref1=pref(1, F_XR))),
        track(pair(320, 120, 300, 60), 5, seg(DRAW, 6, kind=K_RECORD, src=pic, xL=330, xR=346, y=130, lvl0=15, lvl1=15,
                                              pref0=pref(2, F_DXL), pref1=pref(3, F_DXR)))))
    pD = c.add(prog(
        track(pair(0, 200, 300, 60), 6, seg(DRAW, 0, kind=K_RECORD, src=pic, y=210, lvl0=15, lvl1=0,
                                            pref0=pref(3, F_STEPS), pref1=pref(0, F_X))),            # steps 0 and lvl1 0 as sent
        track(pair(320, 200, 300, 60), 7, seg(DRAW, 5, kind=K_RECORD, src=pic, xL=330, xR=346, y=210, lvl0=4, lvl1=0,
                                              pref0=pref(1, F_DX), pref1=pref(2, F_LVL1)))))
    pE = c.add(prog(track(rect(0, 280, 300, 60), 8, drawrec(4, pic, 10, 290, dx=50, pref0=pref(0, F_H0), pref1=pref(1, F_H1)))))
    pF = c.add(prog(track(rect(320, 280, 300, 60), 9, wait(200), drawrec(1, pic, 330, 290, dx=40, pref0=pref(0, F_STEPS)))))
    vec("v3-params", [
        step(*arm, *writes(c)),
        step(tk(2000), msg(play(0, P, pB, 0x0501, P=(pic2, 40, 25, 0x0109)))),    # 1  src, y, dy, lvl0 (the low byte: 9)
        step(tk(2010), msg(play(1, P, pC, 0x0502, P=(20, 40, 150, 170)))),        # 2  xL, xR, dxL, dxR on a per-lens pair
        step(tk(2020), msg(play(2, P, pD, 0x0503, P=(30, 120, 0x7F0C, 0x0105)))), # 3  x both, dx both, lvl1 = 12, steps = 5
        step(tk(2030), msg(play(3, P, pE, 0x0504, P=(0, 0)))),                    # 4  h0 = h1 = 0: accepted
        step(tk(2068), wake()),                                                   # 5
        step(tk(2136), wake()),                                                   # 6
        step(tk(2300), wake()),                                                   # 7  every instance's last step
        step(tk(2400), msg(play(0, P, pB, 0x0505, P=(pic2, 40, 25, 16)))),        # 8  lvl0 = 16 after substitution: 12
        step(tk(2410), msg(play(0, P, pB, 0x0506, P=(pic2, 40, 25, 0x0110)))),    # 9  the low byte 0x10 = 16: 12
        step(tk(2420), msg(play(0, P, pB, 0x0507, P=(0x7FFF, 40, 25, 9)))),       # 10 src substituted past the cache: 5
        step(tk(2430), msg(play(3, P, pE, 0x0508, P=(1, 0)))),                    # 11 h0 = 1: 12
        step(tk(2440), msg(play(3, P, pE, 0x0509, P=(0, 3000)))),                 # 12 h1 = 3000: 12 (build-neutral since C2, B.2: past 2048 on a bit-10 build too)
        step(tk(2450), msg(play(0, 16, pF, 0x050A, P=(56,)))),                    # 13 200 + 56: T = 256: 12
        step(tk(2460), msg(play(0, 16, pF, 0x050B, P=(0x0137,)))),                # 14 the low byte 55: T = 255, accepted
        step(tk(2460 + 16), wake()),                                              # 15
    ])

    # v3-retarget — §5.5 RETARGET "for an open instance (drawing or done): writes END(prog, new P) into the
    # shadow; restarts the instance at step 1 now, each moving segment from the state this lens last drew to the
    # new final state (a leading WAIT restarts whole); END ordinal + 1. Only for programs whose tracks have at most
    # one non-WAIT segment after an optional leading WAIT (else 12)"; §5.6 "after a CLOSE ... refused (16)";
    # decision A.13: with nothing drawn yet (a PLAY and a RETARGET in one batch) each track's start state.
    c = Cache2()
    pic = c.add(image2(40, 30, pattern(40, 30, 351)))
    rt1 = c.add(prog(track(rect(0, 40, 640, 120), 2, drawrec(8, pic, 100, 50, ease=EASE_OUT,
                                                              pref0=pref(0, F_DXL), pref1=pref(1, F_DY)))))
    rt2 = c.add(prog(track(rect(0, 200, 640, 60), 3, drawrec(3, pic, 0, 210, dx=100), drawrec(3, pic, 100, 210, dx=100))))
    rt3 = c.add(prog(track(rect(0, 300, 640, 60), 4, wait(3), drawrec(5, pic, 50, 310, pref0=pref(0, F_DXL)))))
    rt4 = c.add(prog(track(rect(0, 380, 300, 60), 5, drawrec(6, pic, 10, 390, pref0=pref(0, F_DXL))),
                     track(rect(320, 380, 300, 60), 6, wait(2), drawrec(4, pic, 330, 390, pref0=pref(1, F_DXL)))))
    vec("v3-retarget", [
        step(*arm, *writes(c)),
        step(tk(2000), msg(play(0, P, rt1, 0x0601, P=(300, 20)))),      # 1
        step(tk(2000 + P), wake()),                                     # 2  step 2
        step(tk(2000 + 2 * P), wake()),                                 # 3  step 3
        step(tk(2080), msg(retarget(0, (450, 50)))),                    # 4  drawing: END(new P); step 1 again from step 3's state; ordinal 1
        step(tk(2080 + P), wake()),                                     # 5
        step(tk(2080 + 7 * P), wake()),                                 # 6  its last step: the notify carries ordinal 1
        step(tk(2500), msg(retarget(0, (-60, -10)))),                   # 7  done: a new motion from the final state; ordinal 2
        step(tk(2500 + P), wake()),                                     # 8
        step(tk(2500 + 7 * P), wake()),                                 # 9  ordinal 2's last step
        step(tk(2800), msg(close(0)), tk(2800), msg(retarget(0, (10, 10)))),       # 10 after a CLOSE: 16
        step(tk(2900), msg(play(1, P, rt2, 0x0602)), tk(2900), msg(retarget(1))),  # 11 two non-WAIT segments in a track: 12
        step(tk(3000), msg(play(2, P, rt3, 0x0603, P=(200,)))),         # 12 a leading WAIT, then a DRAW
        step(tk(3000 + 4 * P), wake()),                                 # 13 step 5: the DRAW's second step
        step(tk(3000 + 4 * P + 10), msg(retarget(2, (400,)))),          # 14 a leading WAIT restarts whole
        step(tk(3000 + 4 * P + 10 + 3 * P), wake()),                    # 15 step 4: the DRAW's first again
        step(tk(3000 + 4 * P + 10 + 7 * P), wake()),                    # 16 step 8 = T
        step(tk(3500), msg(batch(play(3, P, rt4, 0x0604, P=(200, 250)), retarget(3, (-100, 100))))),   # 17 nothing drawn yet (A.13)
        step(tk(3500 + P), wake()),                                     # 18
        step(tk(3500 + 5 * P), wake()),                                 # 19 T = 6
    ])

    # v3-close — §5.5 CLOSE "empties the slot whatever this lens was drawing ...; a drawing instance's notify says
    # outcome 1, a done one sends none"; §5.5 PLAY "closes the slot's instance, every open instance whose areas
    # meet this one's"; §5.6 "A live keyframe (mode 6, not a self-test step's) or mode 11 closes every instance";
    # decision A.7 (mode 11 a release point, no notify), A.8 (mode 6 decided from the bytes); §5.6 "a cache write
    # (12, 19, 25) whose entries meet an open instance's read ranges closes that instance first ... a write between
    # two of its records closes it". Read ranges are half-open: a write at the first range's end meets nothing.
    c = Cache2()
    px = c.add(image2(30, 20, pattern(30, 20, 361)))
    c1 = c.add(prog(track(rect(0, 20, 200, 60), 2, drawrec(8, px, 10, 30, dx=150))))
    c2 = c.add(prog(track(rect(220, 20, 200, 60), 3, drawrec(8, px, 230, 30, dx=150))))
    c3 = c.add(prog(track(rect(440, 20, 180, 60), 4, drawrec(2, px, 450, 30, dx=100))))
    c4 = c.add(prog(track(rect(100, 40, 200, 60), 5, drawrec(8, px, 110, 50, dx=150))))    # meets c1's area
    recA = c.add(image2(20, 20, pattern(20, 20, 362)))
    filler_bytes = image2(20, 20, pattern(20, 20, 363))
    filler = c.add(filler_bytes)                                        # named by no program: between recA and recB
    recB = c.add(image2(20, 20, pattern(20, 20, 364)))
    c5 = c.add(prog(track(rect(0, 120, 300, 60), 6, drawrec(8, recA, 10, 130, dx=200)),
                    track(rect(320, 120, 300, 60), 7, drawrec(8, recB, 330, 130, dx=200))))
    assert len(c.buf) % 4 == 0
    beside = c.reserve(8)                                               # starts exactly at c5's end: outside its first range
    assert beside * 4 == c5 * 4 + len(c.entries[-1][1])
    recC_bytes = image2(20, 20, pattern(20, 20, 365))
    recC = c.add(recC_bytes)
    c7 = c.add(prog(track(rect(220, 200, 200, 60), 8, drawrec(8, recC, 230, 210, dx=100))))
    c6 = c.add(prog(track(rect(0, 200, 200, 60), BD_SELF, drawself(8, 0, 200, dx=80))))
    ccap = c.reserve(cap_total((200, 60)))
    assert recC * 4 + len(recC_bytes) <= 65536                          # mode 12 reaches it (the v1 window)
    vec("v3-close", [
        step(*[flags(DRAW2 | ENGINE | ZWRITE) if "flags" in o else o for o in arm], *writes(c)),
        step(tk(2000), msg(play(0, P, c1, 0x0701)), tk(2000), msg(play(1, P, c2, 0x0702)), tk(2000), msg(play(2, P, c3, 0x0703))),   # 1
        step(tk(2000 + P), wake()),                                     # 2  c3's last step (T = 2): done
        step(tk(2050), msg(close(0))),                                  # 3  drawing: outcome 1; the shadow (END) shown whole
        step(tk(2060), msg(close(2))),                                  # 4  done: no notify
        step(tk(2070), msg(close(0xFF))),                               # 5  all: c2 drawing, outcome 1
        step(tk(2080), msg(close(1))),                                  # 6  an empty slot: nothing
        step(tk(2100), msg(play(0, P, c1, 0x0704))),                    # 7
        step(tk(2110), msg(play(1, P, c4, 0x0705))),                    # 8  over c1's area: c1 closes (outcome 1)
        step(tk(2120), msg(play(1, P, c2, 0x0706))),                    # 9  into c4's slot: c4 closes (outcome 1)
        step(tk(2130), msg(play(0, P, c1, 0x0707)), msg(kf)),           # 10 a live mode 6 closes every instance
        step(tk(2200), msg(play(0, P, c1, 0x0708)), msg(bytes([16, 0])), msg(bytes([16, 1]) + kf), msg(bytes([16, 2]))),   # 11 a self-test step's mode 6 closes nothing
        step(tk(2200 + P), wake()),                                     # 12 c1 still draws: step 2
        step(tk(2300), msg(play(0, P, c5, 0x0709)), tk(2300), msg(play(1, P, c6, 0x070A, ccap)),
             tk(2300), msg(play(2, P, c7, 0x070B))),                    # 13
        step(tk(2310), msg(cache_write2((beside, bytes(8))))),          # 14 at c5's first range's end: closes nothing
        step(tk(2300 + P), wake()),                                     # 15 c5, c6, c7 all still draw
        step(tk(2340), msg(cache_write2((filler, filler_bytes)))),      # 16 between c5's two records: c5 closes
        step(tk(2350), msg(cache_write((recC * 4, recC_bytes)))),       # 17 mode 12 over c7's record (the same bytes): c7 closes
        step(tk(2360), msg(zwrite((ccap + 10, bytes(16))))),            # 18 mode 25 inside c6's capture: c6 closes
        step(tk(2300 + 2 * P), wake()),                                 # 19 nothing drawing: nothing presents
        step(tk(2400), msg(play(0, P, c1, 0x070C)), msg(bytes([11]))),  # 20 mode 11: a release point, no notify
    ])

    # v3-order — §5.5 "Check order (the reason recorded is part of the contract): length (1) → a self-test step (8)
    # → the lease (3) → DRAW2 (9) → ENGINE (15) → slot, period, P values (12) → for RETARGET an empty slot (16) →
    # the program record and every record and table it names, inside the cache and well formed (5) → areas ... (2)
    # → a `none` backdrop's area ... (2) → every step's values in range (12) → ... the capture range (5) → for a
    # RETARGET, the new first range not meeting the instance's capture range (5)"; decisions A.6 (no shadow first:
    # host only, the vectors always hold one), A.9 (the 5/12 split), A.10 (no range on P0..P3). Each refusal also
    # carries a later check's fault, so only the stated order records its reason, and each is followed by an
    # accepted PLAY whose present draws step 1 (a refusal exit that kept the engine word would fold that step).
    c = Cache2()
    pic = c.add(image2(40, 30, pattern(40, 30, 371)))
    tbl = c.add(easing([1000, 2000, 3000, 4096]))
    ovt = c.add(easing([1500, 3500, 4600, 4096]))                       # E above 4096: an extent past F, a level past 15
    ok = c.add(prog(track(rect(0, 400, 300, 40), 2, drawrec(4, pic, 10, 405, dx=200))))   # T = 4, step 1 differs from END
    good_t = lambda **extra: track(rect(0, 40, 200, 40), 3, drawrec(4, pic, 10, 45, dx=100, **extra))
    def bp(*tr, **kw): return c.add(prog(*tr, bad=True, **kw))
    def gp(*tr): return c.add(prog(*tr))
    offp = rect(600, 40, 100, 40)                                       # off the panel
    lvl16 = drawrec(4, pic, 10, 45, lvl=(15, 16))
    refusals = [
        # (ops before, the refused message, what it proves)
        ([], bad(play(9, 15, 0xFFFF, 0)[:-1])),                         # length (1) before slot, period, record
        ([], bad(play(0, P, ok, 0) + b"\x00")),                         # one byte long: 1
        ([], bad(bytes([26]))),                                         # no sub: 1
        ([], bad(retarget(9)[:-1])),                                    # 1 before slot (12)
        ([], bad(close(9) + b"\x00")),                                  # 1
        ([], bad(bytes([26, 2]))),                                      # a CLOSE with no slot: 1
        ([msg(bytes([16, 0]))], bytes([16, 1]) + play(9, 15, 0xFFFF, 0)),   # in a self-test step (8) before slot and record
        ([{"lease": "release"}], play(9, 15, 0xFFFF, 0)),               # the lease (3) before DRAW2, ENGINE, slot
        ([flags(ENGINE)], play(9, 15, 0xFFFF, 0)),                      # DRAW2 (9) before ENGINE and slot
        ([flags(DRAW2)], play(9, 15, 0xFFFF, 0)),                       # ENGINE (15) before slot
        ([flags(DRAW2)], retarget(9)),                                  # 15 for RETARGET
        ([flags(DRAW2)], close(0)),                                     # 15 for CLOSE
        ([], play(4, P, 0xFFFF, 0)),                                    # slot (12) before the record (5)
        ([], play(0, 15, 0xFFFF, 0)),                                   # period 15: 12
        ([], play(0, 129, ok, 0)),                                      # period 129: 12
        ([], retarget(4)),                                              # slot (12) before an empty slot (16)
        ([], close(4)),                                                 # 12
        ([], retarget(3)),                                              # an empty slot: 16
        ([], play(0, P, 0xFFFF, 0)),                                    # the program past the cache: 5
        ([], play(0, P, bp(track(offp, 2, lvl16), version=2), 0)),      # version 2: 5 before areas (2) and values (12)
        ([], play(0, P, bp(ntracks=0), 0)),                             # no tracks: 5
        ([], play(0, P, bp(*[good_t()] * 9), 0)),                       # 9 tracks: 5
        ([], play(0, P, bp(good_t(), length=4 + 44 - 4), 0)),           # its length 4 short: 5
        ([], play(0, P, bp(good_t(), length=4 + 44 + 4, tail=bytes(4)), 0)),   # 4 bytes past its tracks: 5
        ([], play(0, P, bp(track(offp, 2, nseg=0)), 0)),                # a track of 0 segments: 5
        ([], play(0, P, bp(track(offp, 2, drawrec(4, pic, 10, 45), per_lens=2)), 0)),   # perLens 2: 5
        ([], play(0, P, bp(track(offp, 16, drawrec(4, pic, 10, 45))), 0)),             # backdrop 16: 5 (A.9)
        ([], play(0, P, bp(track(offp, 0x21, drawrec(4, pic, 10, 45))), 0)),           # backdrop 0x21: 5
        ([], play(0, P, bp(track(offp, 2, seg(4, 4))), 0)),                            # verb 4: 5
        ([], play(0, P, bp(track(offp, 2, drawrec(4, pic, 10, 45, ease=3))), 0)),      # ease 3: 5
        ([], play(0, P, bp(track(offp, 2, seg(DRAW, 4, kind=1, src=pic, lvl0=15, lvl1=15))), 0)),   # DRAW kind 1: 5 (A.9)
        ([], play(0, P, bp(track(offp, 2, seg(REVEAL, 4, kind=1, src=pic, lvl0=15, lvl1=15))), 0)), # REVEAL kind 1: 5
        ([], play(0, P, bp(track(offp, 2, drawrec(4, 0x3FF0, 10, 45))), 0)),           # a record past the cache: 5
        ([], play(0, P, bp(track(offp, 2, drawrec(4, tbl, 10, 45))), 0)),              # a "record" that is a table (w 4, h 1000): 5
        ([], play(0, P, bp(track(offp, 2, drawrec(4, pic, 10, 45, ease=TABLE, table=0x3FF0))), 0)),   # a table past the cache: 5
        ([], play(0, P, bp(track(offp, 2, drawrec(3, pic, 10, 45, ease=TABLE, table=tbl))), 0)),      # n 4 for 3 steps: 5
        ([], play(0, P, bp(track(offp, 2, drawrec(4, pic, 10, 45), res=1)), 0)),       # a track's reserved byte: 5 (A.9)
        ([], play(0, P, bp(track(offp, 2, drawrec(4, pic, 10, 45, res0=1))), 0)),      # a segment's first reserved byte: 5
        ([], play(0, P, bp(track(offp, 2, drawrec(4, pic, 10, 45, res1=1))), 0)),      # its second: 5
        ([], play(0, P, bp(track(offp, 2, drawrec(4, pic, 10, 45, anchor=3))), 0)),    # anchor 3: 5 (A.9; outside 0..2 on a bit-10 build too, C2 B.2)
        ([], play(0, P, bp(track(offp, 2, drawrec(4, pic, 10, 45, opt=0x01))), 0)),    # an opt bit DRAW does not use: 5
        ([], play(0, P, gp(track((rect(0, 40, 100, 40), rect(560, 40, 100, 40)), 2, drawrec(4, pic, 10, 45, xR=560))), 0)),   # one lens off the panel: 2 on both
        ([], play(0, P, gp(track((rect(0, 40, 100, 40), rect(16, 40, 100, 41)), 2, drawrec(4, pic, 10, 45, xR=16))), 0)),     # a pair of two sizes: 2
        ([], play(0, P, gp(track((rect(0, 40, 100, 40), rect(16, 42, 100, 40)), 2, drawrec(4, pic, 10, 45, xR=16))), 0)),     # two row ranges: 2
        ([], play(0, P, gp(track(rect(0, 40, 0, 40), 2, drawrec(4, pic, 10, 45))), 0)),                # empty: 2
        ([], play(0, P, gp(track(rect(0, 460, 100, 40), 2, lvl16)), 0)),                              # past the bottom (and level 16): 2 before 12
        ([], play(0, P, gp(track(rect(0, 40, 100, 40), BD_NONE, drawrec(4, pic, 10, 45))), 0)),        # the first track's backdrop none: 2
        ([], play(0, P, gp(good_t(), track(rect(150, 40, 100, 40), BD_NONE, lvl16)), 0)),              # none, not inside track 1 (and level 16): 2 before 12
        ([], play(0, P, gp(track((rect(0, 40, 200, 40), rect(16, 40, 200, 40)), 3, drawrec(4, pic, 10, 45, xR=26)),
                         track((rect(10, 45, 50, 20), rect(170, 45, 50, 20)), BD_NONE, drawrec(4, pic, 10, 45, xR=170))), 0)),   # none: LEFT inside, RIGHT not: 2
        ([], play(0, P, gp(good_t(lvl=(15, 16))), 0)),                  # lvl1 16: 12
        ([], play(0, P, gp(track(rect(0, 40, 200, 40), 3, fillseg(4, 16))), 0)),                      # FILL level 16: 12 (A.9: levels)
        ([], play(0, P, gp(track(rect(0, 40, 200, 40), 3, reveal(4, TOP, K_LEVEL, 10, 45, src=16, w=20, h=10))), 0)),   # a level source of 16: 12
        ([], play(0, P, gp(track(rect(0, 40, 200, 40), 3, reveal(4, TOP, K_LEVEL, 10, 45, src=5, w=0, h=10))), 0)),     # its width 0: 12
        ([], play(0, P, gp(track(rect(0, 40, 200, 40), 3, reveal(4, LEFT, K_RECORD, 10, 45, src=pic, ease=TABLE, table=ovt))), 0)),   # an extent past F: 12
        ([], play(0, P, gp(track(rect(0, 40, 200, 40), 3, drawrec(4, pic, 10, 45, lvl=(0, 15), ease=TABLE, table=ovt))), 0)),       # a level past 15: 12
        ([], play(0, P, gp(good_t(h0=1)), 0)),                          # h0 1: 12
        ([], play(0, P, gp(good_t(h1=1)), 0)),                          # h1 1: 12
        ([], play(0, P, gp(track(rect(0, 40, 200, 40), 3, wait(200), drawrec(56, pic, 10, 45))), 0)),   # T 256: 12
    ]
    sc = gp(track(rect(0, 300, 41, 20), BD_SELF, wait(4)), good_t())    # one `self` track: a 21 x 20 capture
    sc16 = gp(track(rect(0, 300, 41, 20), BD_SELF, wait(4)), good_t(lvl=(15, 16)))
    capsz = cap_total((41, 20))
    refusals += [
        ([], play(0, P, sc16, 0xFFFF)),                                 # a capture past the cache with level 16: 12 before 5
        ([], play(0, P, sc, (65536 - capsz) // 4 + 1)),                 # the capture range 4 bytes past 64 KiB: 5
        ([], play(0, P, sc, sc)),                                       # cap = the program itself: meets its first range: 5
    ]
    # the RETARGET case: [program][old record][capture][new record] — the new first range spans the capture
    mr = c.add(prog(track(rect(0, 300, 40, 20), BD_SELF, wait(6)),
                    track(rect(100, 300, 200, 40), 4, drawrec(6, 0, 110, 305, dx=100, pref0=pref(0, F_SRC)))))
    mr_old = c.add(image2(20, 10, pattern(20, 10, 372)))
    mr_cap = c.reserve(cap_total((40, 20)))
    mr_new = c.add(image2(20, 10, pattern(20, 10, 373)))
    st = [step(*arm, *writes(c))]
    t = 2000
    for pre, m in refusals:
        if pre and "lease" in pre[0]:                                   # the release took the cache and the flags
            after = [{"lease": "acquire"}, flags(DRAW2 | ENGINE), msg(kf), *writes(c)]
        elif pre and "flags" in pre[0]:
            after = [flags(DRAW2 | ENGINE)]
        elif pre:                                                       # the self-test begin: end it
            after = [msg(bytes([16, 2]))]
        else:
            after = []
        st.append(step(*pre, tk(t), msg(m), *after, tk(t + 5), msg(play(0, P, ok, 0x0800 + len(st)))))
        st.append(step(tk(t + 5 + 3 * P), wake()))                     # its last step: the next PLAY closes a done one
        t += 200
    st.append(step(tk(t), msg(play(0, 16, ok, 0x08A0)), tk(t), msg(play(0, 128, ok, 0x08A1))))   # periods 16 and 128: accepted
    st.append(step(tk(t + 10), msg(play(1, P, mr, 0x08A2, mr_cap, P=(mr_old,)))))                 # accepted: its first range ends before the capture
    st.append(step(tk(t + 20), msg(retarget(1, (mr_new,))), tk(t + 25), msg(play(0, P, ok, 0x08A3))))   # the new first range meets the capture range: 5
    st.append(step(tk(t + 20 + P), wake()))
    vec("v3-order", st)

    # v3-lifecycle — §5.8 "bit 3 ENGINE (needs DRAW2 armed too)", "A disarm closes nothing ... a PLAY ... handled
    # after it is refused (15)"; §5.6 "A lease lapse, FB_RELEASE, a fresh acquire ... are release points: every
    # instance ends, nothing is drawn after, no notify is sent; ... stock content is never drawn over. A lapse
    # nothing has noticed yet ... meets the hook's own deadline test: that present takes the stock path, draws
    # nothing and turns every drawing instance done with no notify"; decision A.7 (mode 11).
    c = Cache2()
    pic = c.add(image2(40, 30, pattern(40, 30, 381)))
    l1 = c.add(prog(track(rect(0, 40, 300, 60), 2, drawrec(8, pic, 10, 50, dx=200))))
    l2 = c.add(prog(track(rect(320, 40, 300, 60), 3, drawrec(8, pic, 330, 50, dx=200))))
    rearm = lambda: [flags(DRAW2 | ENGINE), msg(kf), *writes(c)]
    vec("v3-lifecycle", [
        step(tk(1000), {"lease": "acquire"}, flags(DRAW2), msg(kf), *writes(c), flags(ENGINE)),   # 0  ENGINE without DRAW2
        step(tk(1100), msg(play(0, P, l1, 0x0901))),                    # 1  DRAW2 (9) before ENGINE
        step(flags(DRAW2 | ENGINE), tk(1200), msg(play(0, P, l1, 0x0902)), tk(1200), msg(play(1, P, l2, 0x0903))),   # 2  both armed
        step(tk(1200 + P), wake()),                                     # 3
        step({"lease": "release"}),                                     # 4  FB_RELEASE: both end, no notify
        step(tk(1200 + 2 * P), wake()),                                 # 5  nothing drawing
        step(stock()),                                                  # 6  the stock repaint
        step(tk(1200 + 3 * P), wake()),                                 # 7  nothing drawn over it
        step(tk(2000), {"lease": "acquire"}, *rearm(), tk(2000), msg(play(0, P, l1, 0x0904)), tk(2000), msg(play(1, P, l2, 0x0905))),   # 8
        step(tk(2000 + P), wake()),                                     # 9
        step(tk(2050), msg(bytes([11]))),                               # 10 mode 11: both end, no notify
        step(tk(2000 + 2 * P), wake()),                                 # 11 nothing drawing
        step(tk(2100), {"lease": "acquire"}, *rearm(), tk(2100), msg(play(0, P, l1, 0x0906))),   # 12
        step(tk(2100 + 90000 + 50), wake()),                            # 13 a lapse nothing noticed: the stock path, l1 done, no notify, W forever
        step(stock()),                                                  # 14
        step(tk(2100 + 90000 + 100), wake()),                           # 15 nothing drawing
        step(tk(100000), {"lease": "acquire"}, *rearm(), tk(100000), msg(play(0, P, l1, 0x0907)), tk(100000), msg(play(1, P, l2, 0x0908))),   # 16
        step(tk(100000 + P), wake()),                                   # 17
        step(tk(100000 + 90000 + 1000), {"lease": "acquire"}),          # 18 a fresh acquire after an unnoticed lapse, instances drawing: they end, no notify
        step(*rearm(), tk(191100), msg(play(0, P, l1, 0x0909))),        # 19
        step(tk(191100 + P), flags(DRAW2)),                             # 20 ENGINE disarmed while l1 draws: closes nothing
        step(tk(191100 + 2 * P), wake()),                               # 21 l1 runs on
        step(tk(191100 + 7 * P), wake()),                               # 22 its last step: outcome 0
        step(tk(191400), msg(play(1, P, l2, 0x090A))),                  # 23 refused: 15
    ])

    # v3-hint — §5.3 "a present that draws a step goes whole (a mode-24 hint is ignored for it)".
    c = Cache2()
    pic = c.add(image2(40, 30, pattern(40, 30, 391)))
    h1 = c.add(prog(track(rect(0, 100, 640, 60), 2, drawrec(8, pic, 10, 110, dx=500))))
    vec("v3-hint", [
        step(*arm, *writes(c)),
        step(tk(2000), msg(play(0, P, h1, 0x0A01))),                    # 1
        step(tk(2000 + P), wake()),                                     # 2
        step(tk(2050), msg(batch(fill(rect(0, 300, 640, 10), 5), fill(rect(0, 400, 640, 10), 9), hint(300, 309)))),   # 3  draws step 2 again: whole (rows 400.. reach the lens)
        step(tk(2000 + 2 * P), wake()),                                 # 4
        step(tk(2090), msg(batch(fill(rect(0, 420, 640, 10), 3), hint(0, 0)))),   # 5  draws step 3 again: whole
        step(tk(2000 + 7 * P), wake()),                                 # 6  the last step
        step(tk(2300), msg(batch(fill(rect(0, 440, 640, 10), 6), hint(440, 449)))),   # 7  stale: whole
        step(tk(2400), msg(batch(fill(rect(0, 450, 640, 10), 7), fill(rect(0, 300, 640, 10), 1), hint(450, 459)))),   # 8  nothing drawing, not stale: rows only
    ])

    # v3-zwrite — §5.9 mode 25: "inflating to exactly a mode-19 entry list (no mode byte), at most 16,384 B. Order:
    # length ≥ 3 (1) → not in a batch or a step (8) → the lease (3) → DRAW2 (9) → ZWRITE (15) → the inflate ...
    # a stream that does not end cleanly or runs past 16,384 B (13) → then mode 19's own checks and semantics on
    # the list (an empty list writes nothing and does not bump; one bump per message)". The same list goes once
    # by mode 19 and once by mode 25 (mode 11 between: a fresh cache), and the same draws follow each.
    c = Cache2()
    zpic = c.add(image2(40, 30, pattern(40, 30, 401)))
    tofu = c.add(cache_image(7, 14, [15 if (x in (0, 6) or y in (0, 13)) else 0 for y in range(14) for x in range(7)]))
    zg = {ch: c.add(cache_image(5 + (ch % 7), 14, pattern(5 + (ch % 7), 14, 410 + ch))) for ch in b"DamgeC1 "}
    ztab = c.reserve(224 * 2)
    c.put(ztab, b"".join(u16(zg.get(ch, tofu)) for ch in range(32, 256)))
    X = list(c.entries)
    assert len(cache_write2(*X)) - 1 <= 16384
    draws = [msg(draw2(zpic, 100, 100, 0x0F)), msg(string2(ztab, 100, 160, 0x0F, b"Damage C1")),
             msg(draw2_pair(zpic, 300, 316, 200, 0x1F))]
    big = bytes((i * 7) & 0xFF for i in range(16381))                   # 4 + 16,380 = 16,384 B inflated; one more is 16,385
    zarm = [{"lease": "acquire"}, flags(DRAW2 | ZWRITE), msg(kf)]
    vec("v3-zwrite", [
        step(tk(1000), *zarm),
        step(msg(cache_write2(*X))),                                    # 1  run 1: the list by mode 19 (one bump)
        step(*draws),                                                   # 2
        step(msg(bytes([11])), *zarm),                                  # 3  mode 11: the cache and the flags go; a fresh keyframe
        step(msg(zwrite(*X))),                                          # 4  run 2: the same list by mode 25 (one bump)
        step(*draws),                                                   # 5  the same shadow as step 2
        step(msg(bytes([25]) + z(b""))),                                # 6  an empty list: accepted, nothing written, no bump
        step(msg(zwrite((4096, big[:16380])))),                         # 7  exactly 16,384 B: accepted
        step(msg(zwrite((4096, big)))),                                 # 8  16,385 B: 13
        step(msg(bad(zwrite((4096, big[:2000]))[:40]))),                # 9  a stream cut short: 13
        step(msg(bad(bytes([25, 0x00]) + zwrite((4096, bytes(64)))[2:]))),   # 10 a bad zlib header: 13
        step(msg(bad(bytes([25]) + z(u16(4096) + u16(100) + bytes(10))))),   # 11 an entry whose data runs past the list: mode 19's 1
        step(msg(zwrite((0xFFFF, bytes(8))))),                          # 12 an entry past the cache: mode 19's 5
        step(msg(bad(bytes([25]))), msg(bad(bytes([25, 0x78])))),       # 13 shorter than 3: 1
        step(msg(batch(fill(rect(0, 0, 8, 8), 3), zwrite((4096, bytes(8)))))),   # 14 in a batch: 8 (the fill already applied)
        step(flags(DRAW2), msg(zwrite((4096, bytes(8))))),              # 15 ZWRITE unarmed: 15
        step(flags(ZWRITE), msg(zwrite((4096, bytes(8))))),             # 16 DRAW2 unarmed: 9
        step(flags(DRAW2 | ZWRITE), msg(bytes([16, 0])), msg(bytes([16, 1]) + zwrite((4096, bytes(8)))), msg(bytes([16, 2]))),   # 17 in a step: 8
        step({"lease": "release"}, msg(zwrite((4096, bytes(8))))),      # 18 no lease: 3
    ], panel=False)

    # v3-cache256 — §5.9 "Op 5 CACHE_SIZE: 64..256 KiB on a bit-8 build (status 5 outside it); the v1 window stays
    # the first 64 KiB". 161 moved here from `v2-cachesize` (decision A.1): on a bit-8 build it is status 0.
    hi = Cache2(start=200000)                                           # past 160 KiB
    hip = hi.add(image2(40, 30, pattern(40, 30, 421)))
    end = Cache2(start=262144 - 8)                                      # a record ending exactly at 256 KiB
    endp = end.add(image2(20, 15, [9] * 300))                           # 4 B of header + one 4-byte run token
    assert len(end.buf) == 262144
    v1g = cache_image(9, 12, pattern(9, 12, 422))
    v1rec = cache_image(20, 20, pattern(20, 20, 423))
    v1end = 65536 - len(v1rec)                                          # a v1 record ending exactly at the window's end
    win_edge = 65536 - 96 * 2
    vec("v3-cache256", [
        step(tk(1000), {"lease": "acquire"}, flags(DRAW2), msg(kf), cachesize(161)),   # 0  161 on a bit-8 build: status 0
        step(cachesize(257)),                                           # 1  past 256: status 5
        step(cachesize(256)),                                           # 2  status 0 (nothing allocated yet)
        step(*[msg(m) for m in hi.messages()], msg(draw2(hip, 10, 10, 0x0F))),        # 3  a record at 200,000 B written and drawn
        step(*[msg(m) for m in end.messages()], msg(draw2(endp, 60, 10, 0x0F))),      # 4  a record ending exactly at 256 KiB
        step(msg(cache_write2((262144 // 4 - 1, bytes(8))))),           # 5  one entry running past 256 KiB: 5
        step(cachesize(128)),                                           # 6  allocated already: status 4
        step(msg(cache_write((0x0808, v1g), (win_edge, bytes([8]) * 192)))),          # 7  mode 12: a table ending exactly at the window's end
        step(msg(draw_string(win_edge, 20, 100, 0x0F, b"AB"))),         # 8  mode 14 at the last offset: draws
        step(msg(draw_string(win_edge + 1, 20, 130, 0x0F, b"AB"))),     # 9  one byte later: 5
        step(msg(cache_write((v1end, v1rec))), msg(draw_image(v1end, 100, 200, 0x0F))),   # 10 mode 13 of a record ending at the window's end: draws
        step(msg(cache_write((v1end + 1, v1rec)))),                     # 11 a mode-12 write one byte past the window: 5
        step(msg(cache_write2((v1end // 4 + 1, v1rec))), msg(draw_image((v1end // 4 + 1) * 4, 100, 260, 0x0F))),   # 12 a v1 record crossing the window's end, written by mode 19: the mode-13 draw refused (5)
    ], panel=False)

    # v3-mech — the focused read's cases (`HANDOFF.md` §81; spec §4): §5.2 "k = min(T, 1 + ⌊(now − t0) / P⌋),
    # now − t0 an unsigned 32-bit difference (the tick wraps ...)" and "w = max(1, (int32_t)(due − now))" (the
    # tick values are uint32 as the firmware's clock reads them, so the wrap is written as the clock shows it);
    # §5.6 "A RETARGET's walk replaces the instance's first range (its parameters may substitute src)"; §5.4 a
    # capture "is not counted in field 17"; decision A.5 (only tracks that use `self` capture, a backdrop alone
    # included; each 4-byte aligned); A.2 (one ui_task capture per slot, the sequence taken at capture, slot order
    # then the refusal; the copy hook's owed record per slot).
    c = Cache2()
    pic = c.add(image2(40, 30, pattern(40, 30, 431)))
    m1 = c.add(prog(track(rect(0, 20, 640, 50), 2, drawrec(8, pic, 10, 30, dx=500))))
    old = c.add(image2(30, 20, pattern(30, 20, 432)))                  # before m2: outside m2's first range once retargeted
    m2 = c.add(prog(track(rect(0, 100, 300, 60), 3, drawrec(8, 0, 10, 110, dx=150, pref0=pref(0, F_SRC)))))
    new = c.add(image2(30, 20, pattern(30, 20, 433)))
    m3 = c.add(prog(track(rect(320, 100, 100, 40), BD_SELF, wait(6)),                     # a backdrop-only `self` track
                    track(rect(440, 100, 180, 60), 4, drawrec(6, 0, 450, 110, dx=100, pref0=pref(0, F_SRC)))))
    rec3 = c.add(image2(30, 20, pattern(30, 20, 434)))
    cap3 = c.reserve(cap_total((100, 40)))
    m4a = c.add(prog(track(rect(0, 200, 300, 40), 5, drawrec(4, pic, 10, 205, dx=200))))
    m4b = c.add(prog(track(rect(320, 200, 300, 40), 6, drawrec(4, pic, 330, 205, dx=200))))
    m6 = c.add(prog(*[track(rect(600 + 10 * i, 300, 3, 3), BD_SELF, wait(2)) for i in range(3)]))   # three 6-byte captures: 22 B aligned, 18 packed
    assert cap_total((3, 3), (3, 3), (3, 3)) == 22
    t0 = 0xFFFFFF00
    vec("v3-mech", [
        step(tk(0xFFFFF000), {"lease": "acquire"}, flags(DRAW2 | ENGINE), msg(kf), *writes(c)),
        step(tk(t0), msg(play(0, 64, m1, 0x0B01))),                     # 1  t0 = 0xFFFFFF00, P = 64: step 5 falls on the wrap
        step(tk(t0 + 64), wake()),                                      # 2  step 2
        step(tk(0xFFFFFFF0), wake()),                                   # 3  k = 4 (3 folded); due t0 + 256 = 0 wraps: W = 16
        step(tk(0x00000005), wake()),                                   # 4  now − t0 = 261 unsigned: k = 5, drawn across the wrap
        step(tk(0x000000C0), wake()),                                   # 5  now − t0 = 448: k = 8 = T
        step(tk(1000), msg(play(1, P, m2, 0x0B02, P=(old,)))),          # 6
        step(tk(1000 + P), wake()),                                     # 7
        step(tk(1050), msg(retarget(1, (new,)))),                       # 8  src → new: the first range becomes [m2, new's end)
        step(tk(1060), msg(cache_write2((old, image2(30, 20, pattern(30, 20, 432)))))),   # 9  over the old record: closes nothing
        step(tk(1050 + P), wake()),                                     # 10 still drawing
        step(tk(1090), msg(cache_write2((new, image2(30, 20, pattern(30, 20, 433)))))),   # 11 over the new one: closes (outcome 1)
        step(tk(1050 + 2 * P), wake()),                                 # 12 nothing drawing
        step(tk(1200), msg(play(2, P, m3, 0x0B03, cap3, P=(rec3,)))),   # 13 the backdrop-only track captures; G unchanged
        step(tk(1210), msg(retarget(2, (cap3 + 2,)))),                  # 14 src into the capture range: 5
        step(tk(1200 + P), wake()),                                     # 15 still drawing
        step(tk(1300), msg(play(3, P, m4a, 0x0B04)), tk(1300), msg(play(0, P, m4b, 0x0B05))),   # 16 slot 3 first, then slot 0
        step(tk(1300 + 3 * P), wake()),                                 # 17 last steps of slots 0, 2 and 3 on one wake: bodies in slot order
        step(tk(1500), msg(play(3, P, m4a, 0x0B06)), tk(1500), msg(play(0, P, m4b, 0x0B07))),   # 18
        step(tk(1520), msg(batch(close(3), play(3, P, m4a, 0x0B08), close(3), play(3, P, m4a, 0x0B09),
                                 close(0), play(4, P, m4a, 0x0B0A)))),  # 19 slot 3 captured twice (the second replaces: a sequence gap), slot 0, then the refusal (12)
        step(tk(1520 + P), wake()),                                     # 20 0x0B09 draws on
        step(tk(1600), msg(play(1, P, m6, 0x0B0B, 65536 // 4 - 5))),   # 21 aligned the captures run 2 bytes past 64 KiB: 5 (packed they would fit)
        step(tk(1610), msg(play(1, P, m6, 0x0B0C, 65536 // 4 - 6))),   # 22 ends at 65,534: accepted; G unchanged
    ])

    # v3-rulings — the lead's rulings on the first meeting (build C1, `C1-R1-decisions.md`), each case no earlier
    # vector reaches: R1.1 "a CLOSE that ends an open instance sets the stale mark, so that present goes whole" (in a
    # hinted batch; a CLOSE of an empty slot ends nothing); R1.3 "ease is checked on every segment; ease 2 names a
    # table whatever the verb (walked, refused 5 when bad, counted in the first read range)"; R1.4 "a mode 25 or 26
    # inside a batch or a self-test step is refused by the container's own list first, whatever its length: in a
    # batch 8 with the batch's byte, in a step 8 with the message's byte"; R1.8 "an unknown mode-26 sub (3..255):
    # length 3, else refused 1; then refused 12; no slot in the notify body"; R1.9 "RETARGET's shape rule is checked
    # right after reason 16 ... the shapes are [W], [X], [W,X] only"; R1.10 "a RETARGET overshoot that takes a level
    # out of 0..15 is clamped" (and an extent out of 0..F); R1.20 "a PLAY's own closes run in slot order; a level
    # source's width on a per-lens track is dxL on both lenses; ... the order of sends within one message is the
    # host's order".
    c = Cache2()
    solid = c.add(image2(8, 4, [15] * 32))
    q1 = c.add(prog(track(rect(0, 20, 60, 10), 4, fillseg(2, 9))))
    f3 = c.add(prog(track(rect(0, 40, 60, 10), 4, seg(FILL, 4, ease=3, kind=9))))                 # ease 3 on a FILL: 5
    t3bad = c.add(easing([1000, 2000, 4096]))                                                   # n = 3
    w3 = c.add(prog(track(rect(0, 40, 60, 10), 4, seg(WAIT, 4, ease=TABLE, table=t3bad), fillseg(1, 9))))   # n ≠ steps: 5
    tab3_bytes = easing([1000, 2000, 3000, 4096])
    tab3 = c.add(tab3_bytes)                                                                    # before g3: only its table puts it in g3's first range
    g3 = c.add(prog(track(rect(0, 60, 60, 10), 4, seg(FILL, 4, ease=TABLE, kind=9, table=tab3))))
    rA = c.add(prog(track(rect(0, 80, 200, 10), 4, drawrec(2, 0, 10, 80, dx=10, pref0=pref(0, F_SRC)),
                          drawrec(2, 0, 20, 80, dx=10, pref0=pref(0, F_SRC)))))                  # two moving segments
    rB = c.add(prog(track(rect(0, 100, 200, 10), 4, drawrec(2, solid, 10, 100, dx=10), wait(2))))   # a trailing WAIT
    rC = c.add(prog(track(rect(0, 120, 200, 10), 4, wait(2), wait(2))))                         # [W, W]
    rD = c.add(prog(track(rect(0, 140, 200, 10), 4, wait(3))))                                  # [W]
    ov = c.add(easing([900, 2300, 3500, 4300, 4620, 4600, 4420, 4096]))                          # overshoot past 4233: 0 → 15 reaches 16 and 17
    pL = c.add(prog(track(rect(0, 160, 200, 10), 0, drawrec(8, solid, 10, 163, lvl=(0, 0), ease=TABLE, table=ov,
                                                          pref0=pref(0, F_LVL0), pref1=pref(1, F_LVL1)))))
    pE = c.add(prog(track(rect(300, 160, 100, 40), 0, seg(REVEAL, 5, LINEAR, K_LEVEL, src=11, xL=310, y=165, dxL=30, dy=20,
                                                          lvl0=15, lvl1=15, opt=SLIDE | TOP, pref0=pref(0, F_DY)))))
    s0 = c.add(prog(track(rect(400, 20, 60, 10), 4, fillseg(50, 9))))
    s2 = c.add(prog(track(rect(480, 20, 60, 10), 4, fillseg(50, 10))))
    s1b = c.add(prog(track(rect(560, 20, 40, 10), 4, fillseg(50, 11))))
    s1 = c.add(prog(track(rect(450, 20, 40, 10), 4, fillseg(50, 12))))                          # meets s0's and s2's areas
    pl = c.add(prog(track((rect(20, 200, 100, 20), rect(36, 200, 100, 20)), 2,
                          seg(REVEAL, 3, LINEAR, K_LEVEL, src=12, xL=30, xR=46, y=205, dxL=30, dxR=50, dy=6, lvl0=15, lvl1=15, opt=LEFT))))
    t1b = c.add(prog(track(rect(400, 80, 40, 10), 5, fillseg(40, 7))))
    t1 = c.add(prog(track(rect(400, 60, 40, 10), 5, fillseg(1, 7))))                            # T = 1: the PLAY's present draws its last step
    st26_short, st25_short = bad(bytes([26, 0])), bad(bytes([25, 0x78]))
    rt = 2039                                                                                   # pL's RETARGET tick (t0 of the restart)
    re_ = 2500 + 2 * P + 5                                                                      # pE's RETARGET tick
    vec("v3-rulings", [
        step(*arm, *writes(c)),
        # sub 0x40, not 3: unknown on a bit-10 build too, where 3..5 are COMMIT, DISCARD, SETTLE (C2 B.2; v4-fail has sub 3)
        step(tk(1100), msg(bad(bytes([26, 0x40, 0])))),                  # 1  R1.8 an unknown sub of length 3: 12, no slot in the body
        step(tk(1110), msg(bad(bytes([26, 0x40])))),                     # 2  of length 2: 1
        step(tk(1120), msg(bad(bytes([26, 7, 1, 2, 3])))),               # 3  of length 5: 1, no slot in the body
        step(tk(1130), msg(bad(bytes([26, 0x80, 2])))),                  # 4  sub 0x80, length 3: 12, no slot
        step(flags(DRAW2), tk(1140), msg(bad(bytes([26, 0x40, 0]))), flags(DRAW2 | ENGINE)),   # 5  ENGINE not armed: 15 before the 12
        step(tk(1200), msg(batch(fill(rect(0, 220, 8, 2), 3), st25_short))),  # 6  R1.4 a short mode 25 in a batch: 8, the batch's byte
        step(tk(1210), msg(bytes([16, 0])), msg(bytes([16, 1]) + st26_short), msg(bytes([16, 1]) + st25_short), msg(bytes([16, 2]))),  # 7  short 26 and 25 in steps: 8, the message's byte
        step(tk(1300), msg(play(0, P, q1, 0x0D01))),                     # 8  R1.1
        step(tk(1300 + P), wake()),                                     # 9  its last step: the stale mark
        step(tk(1400), msg(batch(fill(rect(0, 400, 640, 4), 3), hint(400, 403)))),   # 10 whole: the mark is spent
        step(tk(1410), msg(batch(fill(rect(0, 300, 8, 8), 9), fill(rect(0, 410, 640, 2), 4), hint(410, 411)))),   # 11 rows only: level 9 stays off the lens
        step(tk(1420), msg(batch(close(0), fill(rect(0, 420, 640, 2), 5), hint(420, 421)))),   # 12 a CLOSE that ends a done instance: whole, level 9 reaches the lens
        step(tk(1430), msg(batch(fill(rect(0, 300, 8, 8), 11), close(1), fill(rect(0, 430, 640, 2), 6), hint(430, 431)))),   # 13 a CLOSE of an empty slot: rows only, 11 stays off
        step(tk(1500), msg(play(0, P, f3, 0x0D02))),                     # 14 R1.3 ease 3 on a FILL: 5
        step(tk(1510), msg(play(0, P, w3, 0x0D03))),                     # 15 ease 2 on a WAIT names a table of 3 for 4 steps: 5
        step(tk(1520), msg(play(0, P, g3, 0x0D04))),                     # 16 ease 2 on a FILL, a good table: accepted
        step(tk(1530), msg(cache_write2((tab3, tab3_bytes)))),          # 17 a write over the table: in the first range, closes (outcome 1)
        step(tk(1600), msg(play(1, P, rA, 0x0D05, P=(solid,)))),        # 18 R1.9
        step(tk(1610), msg(retarget(1, (0x7FFF,)))),                     # 19 two moving segments, the new P's walk failing too: 12 (the shape first)
        step(tk(1620), msg(play(2, P, rB, 0x0D06)), tk(1620), msg(retarget(2))),   # 20 a trailing WAIT: 12
        step(tk(1630), msg(play(3, P, rC, 0x0D07)), tk(1630), msg(retarget(3))),   # 21 [W, W]: 12
        step(tk(1640), msg(play(0, P, rD, 0x0D08)), tk(1640), msg(retarget(0))),   # 22 [W]: accepted, ordinal 1
        step(tk(1700), msg(batch(close(0xFF)))),                         # 23 every instance closed (outcome 1 for the drawing ones)
        step(tk(2000), msg(play(0, P, pL, 0x0D09, P=(0, 0)))),           # 24 R1.10 a level held at 0
        step(tk(2000 + P), wake()),                                     # 25 step 2: drawn at level 0
        step(tk(rt), msg(retarget(0, (15, 15)))),                       # 26 0 → 15 by the overshoot table: step 1 at level 3
        step(tk(rt + 3 * P), wake()),                                   # 27 step 4: 16, clamped to 15
        step(tk(rt + 4 * P), wake()),                                   # 28 step 5: 17, clamped to 15
        step(tk(rt + 7 * P), wake()),                                   # 29 step 8 = T
        step(tk(2500), msg(play(1, P, pE, 0x0D0A, P=(20,)))),            # 30 a level source sliding in, F = 20
        step(tk(2500 + 2 * P), wake()),                                 # 31 step 3: e = 12
        step(tk(re_), msg(retarget(1, (4,)))),                          # 32 F = 4 now: step 1 eases from 12 to 4 — 10, clamped to 4
        step(tk(re_ + P), wake()),                                      # 33 step 2: 9, clamped to 4
        step(tk(re_ + 4 * P), wake()),                                  # 34 step 5 = T
        step(tk(3000), msg(play(0, P, s0, 0x0D0B)), tk(3000), msg(play(2, P, s2, 0x0D0C)), tk(3000), msg(play(1, P, s1b, 0x0D0D))),   # 35 R1.20
        step(tk(3010), msg(play(1, P, s1, 0x0D0E))),                     # 36 closes slots 0, 1 and 2 in one pass in slot order: the sequences
        step(tk(3020), msg(batch(close(0xFF)))),                         # 37
        step(tk(3100), msg(play(0, P, pl, 0x0D0F))),                     # 38 a level source on a per-lens track: width dxL on both lenses
        step(tk(3100 + 2 * P), wake()),                                 # 39 T = 3
        step(tk(3200), msg(play(3, P, t1b, 0x0D10))),                    # 40
        step(tk(3210), msg(play(3, P, t1, 0x0D11))),                     # 41 closes t1b (the ui_task's body) and draws its own last step (the hook's): the host's order
    ])

    # v3-lease — ruling R1.7: the lease on the vectors' OS-tick clock is the firmware's 90,000 ticks with a signed
    # 32-bit compare (`FIRMWARE.md` §3: "the 90,000-tick lease is 87.9 s"): a message 88,000 and 89,999 ticks after
    # an acquire finds it live, one 90,000 ticks after it lapsed — across the tick's wrap too. Ruling R1.20: "the
    # direct path needs a live lease, as §5.6 words it" (§5.6: the copy hook draws only over a Damage frame it copied
    # or preserves, "`direct_active` with a live lease"): a lapse no message has noticed, then a v1 frame (which reads
    # no lease), draws no step and turns the instance done with no notify (§5.3). No panel key: the panel after a
    # present without a live lease is outside these rulings.
    c = Cache2()
    lp = c.add(prog(track(rect(100, 100, 200, 40), 3, fillseg(200, 9))))   # T = 200 at P = 128: 25,472 ticks
    a0 = 0xFFFF0000
    M32 = 0xFFFFFFFF
    rearm = lambda t: (tk(t), {"lease": "acquire"}, flags(DRAW2 | ENGINE), msg(kf), *writes(c))
    vec("v3-lease", [
        step(*rearm(a0)),                                               # 0  the deadline wraps: 24,464
        step(tk((a0 + 88000) & M32), msg(fill(rect(0, 400, 8, 2), 3))),  # 1  88,000 ticks on, across the wrap: live
        step(tk((a0 + 89999) & M32), msg(fill(rect(0, 404, 8, 2), 4))),  # 2  89,999: live
        step(tk((a0 + 90000) & M32), msg(fill(rect(0, 408, 8, 2), 5))),  # 3  90,000: lapsed (3), a release point
        step(*rearm(30000)),                                            # 4  a fresh acquire: the deadline 120,000
        step(tk(30000 + 88000), msg(fill(rect(8, 400, 8, 2), 6))),       # 5  88,000 ticks on: live (past 87,891)
        step(tk(30000 + 89999), msg(fill(rect(8, 404, 8, 2), 7))),       # 6  89,999: live
        step(tk(30000 + 90000), msg(fill(rect(8, 408, 8, 2), 8))),       # 7  90,000: lapsed (3)
        step(*rearm(130000)),                                           # 8  the deadline 220,000
        step(tk(210000), msg(play(0, 128, lp, 0x0E01))),                 # 9  would end at 235,472
        step(tk(210000 + 5 * 128), wake()),                             # 10 step 6, the lease live
        step(tk(220100), msg(copy((0, 440, 8, 2), (8, 440, 8, 2)))),     # 11 R1.20 a v1 copy after the unnoticed lapse: no step, done, no notify
        step(tk(220200), wake()),                                       # 12 nothing drawing: nothing presents
        step(tk(220300), msg(fill(rect(0, 412, 8, 2), 9))),              # 13 a v2 op notices the lapse: 3
    ], panel=False)

    # v3-rulings2 — the lead's rulings on review pass 1 (build C1, `C1-R2-decisions.md` §B, §C), each case no earlier
    # vector reaches. B-N13: "every close of an open instance sets the stale mark (CLOSE, a PLAY's own closes, a live
    # mode 6's closes, a cache write that meets an instance)" — a cache write that meets a drawing instance, then a
    # hinted batch with nothing else drawing (the panel still shows step 1 there; whole, it shows END); the same over
    # a done instance (a level written outside the hint reaches the lens only when the batch goes whole); a live
    # keyframe inside a hinted batch closing a drawing instance. R1.9's shapes "[W], [X], [W,X] only": two
    # three-segment tracks, [W, X, W] and [W, W, X], refused 12. R1.10's clamp below 0: a RETARGET from level 15 to
    # level 0 by an overshooting table (§5.4 `v(j) = v0 + ((D·E[j] + 2048) >> 12)`: steps 4 to 7 fall to −1 or −2,
    # clamped to 0). R1.20's direct path with a live lease, the panel compared this time: a v1 frame 90,001 ticks
    # after the acquire while an instance draws (`C1-R2-decisions.md` §A: "the lapsed direct path's whole refresh
    # stands as built"). No extent-below-0 step: no accepted message reaches one (§5.5's value check bounds the
    # program's own path from 0, and a restart's extent stays between it and its end — the stage's report).
    c = Cache2()
    solid = c.add(image2(8, 4, [15] * 32))
    mv_bytes = prog(track(rect(0, 20, 200, 10), 4, drawrec(4, solid, 10, 22, dx=120)))      # step 1 at x 40, END at x 130
    mv = c.add(mv_bytes)
    t1_bytes = prog(track(rect(300, 20, 60, 10), 5, fillseg(1, 7)))                          # T = 1
    t1 = c.add(t1_bytes)
    kd = c.add(prog(track(rect(0, 60, 200, 10), 4, drawrec(4, solid, 10, 62, dx=120))))
    r3a = c.add(prog(track(rect(0, 100, 200, 10), 4, wait(2), drawrec(2, solid, 10, 102, dx=40), wait(2))))   # [W, X, W]
    r3b = c.add(prog(track(rect(0, 120, 200, 10), 4, wait(2), wait(2), drawrec(2, solid, 10, 122, dx=40))))   # [W, W, X]
    ov = c.add(easing([900, 2300, 3500, 4300, 4620, 4600, 4420, 4096]))                          # 15 → 0: 12 7 2 −1 −2 −2 −1 0
    # a source of mixed levels: at level 0 (clamped) every pixel draws 0; a level of −1 or −2 taken as an options byte
    # would set §2's inverse and transparent bits and draw 15 − c (a solid 15 source draws 0 either way, unseen)
    mixed = c.add(image2(8, 4, pattern(8, 4, 43)))
    lv = c.add(prog(track(rect(0, 160, 200, 10), 0, drawrec(8, mixed, 10, 163, lvl=(15, 15), ease=TABLE, table=ov,
                                                          pref0=pref(0, F_LVL0), pref1=pref(1, F_LVL1)))))
    kf7 = keyframe(pattern(W, H, 7))
    c2 = Cache2()
    lp = c2.add(prog(track(rect(100, 300, 200, 40), 3, fillseg(200, 9))))  # T = 200 at P = 128: 25,472 ticks
    rt = 2039                                                             # lv's RETARGET tick (t0 of the restart)
    ta = 300000                                                           # the last acquire: its deadline ta + 90,000
    vec("v3-rulings2", [
        step(*arm, *writes(c)),
        step(tk(1100), msg(play(0, P, mv, 0x0F01))),                     # 1  B-N13 step 1 drawn (x 40), END in the shadow
        step(tk(1105), msg(cache_write2((mv, mv_bytes)))),               # 2  a write that meets it: closed (outcome 1), the stale mark
        step(tk(1110), msg(batch(fill(rect(0, 440, 640, 2), 5), hint(440, 441)))),   # 3  nothing drawing: whole, the lens shows END at x 130
        step(tk(1200), msg(play(1, P, t1, 0x0F02))),                     # 4  T = 1: the PLAY's present draws its last step (A.12's mark)
        step(tk(1210), msg(batch(fill(rect(0, 444, 640, 2), 6), hint(444, 445)))),   # 5  whole: the mark is spent
        step(tk(1220), msg(batch(fill(rect(0, 300, 8, 8), 9), fill(rect(0, 448, 640, 2), 7), hint(448, 449)))),   # 6  rows only: level 9 stays off the lens
        step(tk(1230), msg(cache_write2((t1, t1_bytes)))),               # 7  a write that meets the done instance: closed, no notify, the mark
        step(tk(1240), msg(batch(fill(rect(0, 452, 640, 2), 8), hint(452, 453)))),   # 8  whole: level 9 reaches the lens
        step(tk(1300), msg(play(0, P, kd, 0x0F03))),                     # 9
        step(tk(1305), msg(batch(kf7, hint(0, 1)))),                     # 10 a live keyframe in a hinted batch closes it (outcome 1): whole
        step(tk(1400), msg(play(1, P, r3a, 0x0F04)), tk(1400), msg(retarget(1))),   # 11 R1.9 [W, X, W]: 12
        step(tk(1410), msg(play(2, P, r3b, 0x0F05)), tk(1410), msg(retarget(2))),   # 12 [W, W, X]: 12
        step(tk(1420), msg(batch(close(0xFF)))),                         # 13 both closed (outcome 1 each)
        step(tk(2000), msg(play(0, P, lv, 0x0F06, P=(15, 15)))),         # 14 R1.10 a level held at 15
        step(tk(2000 + P), wake()),                                     # 15 step 2: drawn at level 15
        step(tk(rt), msg(retarget(0, (0, 0)))),                         # 16 15 → 0 by the overshoot table: step 1 at level 12
        step(tk(rt + 3 * P), wake()),                                   # 17 step 4: −1, clamped to 0
        step(tk(rt + 4 * P), wake()),                                   # 18 step 5: −2, clamped to 0
        step(tk(rt + 7 * P), wake()),                                   # 19 step 8 = T: 0
        step(tk(ta), {"lease": "acquire"}, flags(DRAW2 | ENGINE), msg(kf), *writes(c2)),   # 20 a fresh acquire
        step(tk(ta + 80000), msg(play(0, 128, lp, 0x0F07))),             # 21 would end at ta + 105,472
        step(tk(ta + 80000 + 5 * 128), wake()),                         # 22 step 6, the lease live
        step(tk(ta + 90001), msg(copy((0, 440, 8, 2), (8, 440, 8, 2)))),  # 23 R1.20 a v1 copy 90,001 ticks on: no step, done, no notify
        step(tk(ta + 90100), wake()),                                   # 24 nothing drawing: nothing presents
        step(tk(ta + 90200), msg(fill(rect(0, 412, 8, 2), 9))),          # 25 a v2 op notices the lapse: 3
    ])

    # v3-rulings3 — review pass 2's two inputs (build C1, `C1-R5-decisions.md` §B B-N7; `C1-R4-decisions.md` item 3).
    # B-N7: R1.4 "a mode 25 or 26 inside a batch or a self-test step is refused by the container's own list first,
    # whatever its length: in a batch 8 with the batch's byte, in a step 8 with the message's byte" — a mode 26 inside a
    # batch inside a self-test step is both at once, and no ruling or vector says which list and which length check
    # come first there: a short one (one byte: 1 by its own length) and a CLOSE of its own length (3 B), each after a
    # fill in the batch. R4 item 3: decision A.12's stale mark at the direct path's latch (§5.3: "the present after an
    # instance's last step marks the panel stale"; A.12: "the present that draws an instance's last step sets it, so
    # the next present goes whole") — a PLAY of a 2-step program; at step 2's due time a top-level fill inside its area,
    # whose own present draws step 2 = T over the fill on the direct path (the copy hook's latch); then a hinted batch
    # elsewhere, whole only if that present set the mark (the fill reaches the lens: the panel equals the shadow; rows
    # only, the lens keeps step T over it); then a second, the mark spent: rows only (level 13 stays off the lens).
    c = Cache2()
    p2 = c.add(prog(track(rect(200, 200, 120, 40), 4, fillseg(2, 11))))  # T = 2: step T paints the area 11
    vec("v3-rulings3", [
        step(*arm, *writes(c)),
        step(tk(1100), msg(bytes([16, 0])), msg(bytes([16, 1]) + batch(fill(rect(0, 380, 8, 2), 3), bad(bytes([26])))),
             msg(bytes([16, 2]))),                                     # 1  B-N7 a one-byte mode 26 in a batch in a step
        step(tk(1110), msg(bytes([16, 0])), msg(bytes([16, 1]) + batch(fill(rect(8, 380, 8, 2), 4), close(1))),
             msg(bytes([16, 2]))),                                     # 2  a CLOSE of its own length in a batch in a step
        step(tk(1200), msg(play(0, P, p2, 0x1001))),                     # 3  R4.3 step 1 drawn by the PLAY's present
        step(tk(1200 + P), msg(fill(rect(220, 210, 40, 20), 9))),        # 4  at step 2's due time: the fill's present draws step T over it
        step(tk(1300), msg(batch(fill(rect(0, 440, 640, 2), 5), hint(440, 441)))),   # 5  whole if the latch set the mark: level 9 reaches the lens
        step(tk(1310), msg(batch(fill(rect(0, 300, 8, 8), 13), fill(rect(0, 444, 640, 2), 6), hint(444, 445)))),   # 6  the mark spent: rows only
    ])

    for vec_ in v:
        vec_["contract"] = 2                                            # §5.1: added ops and bits do not bump the contract
        vec_["start"] = "zero"
        vec_["features"] = [FEATURES_C1, FEATURES_C2, FEATURES_C3]     # C2 B.2, C3 E.4 A16 (§7.13): build-neutral, run at each
    return v

# ---- C2: local input (`FIRMWARE.md` §6; build C2's spec §3 DV and §4, `C2-decisions.md` B.2, B.7) ---------------
# Written from §6.2-§6.6 and §6.10 alone. The vector form adds (§6.10, §9): the op {"input": [devType, eventId,
# value]} — the record through the hook on both lenses, {"input": …, "only": "L"} on LEFT alone (a dropped inter-lens
# answer) — after a `tick` op in its step; the op {"page": on} (true: the EvenHub page in front, false: a stock page);
# a vector's "features" as a list of the builds it answers alike on (v3 since C2); and the expectations "N" (115
# bodies too), "K" (the stock dispatcher's calls per lens in the step), the C's to fill. Every v4 vector sets the page
# explicitly in its first step, so none depends on a harness's default.
BIND = 0x0010                              # §6.8 flag bit 4
FEATURES_C2 = 0x5FF                        # §6.8: C1's 0x1ff plus bit 10 (bit 9, the link item, still out)
FEATURES_C3 = 0xDFF                        # §7.9: C2's 0x5ff plus bit 11 (the v3/v4 sets answer alike on it, §7.13)
TAP, DOUBLE, LONG, UP, DOWN, PRESS, RELEASE = 0, 1, 3, 4, 5, 0xD, 0xE    # §6.1 stock's event ids (4 UPROLL, 5 DOWNROLL)
TOUCH_L, TOUCH_R, RING, ANY = 0, 1, 4, 0xFF                              # §6.1 devTypes; 0xFF a scroll entry's "any" (§6.3)
NONE = 0xFFFF                              # §6.3 LIST's "none"; a tap entry's prog for the report-only form
K_ARG, K_ITEM, K_PITCH = 0, 1, 2           # §6.2 step 9: arg · the item table at c' + arg · c' × pitch + arg
M32 = 0xFFFFFFFF
PAST = 0x4000                              # off4 of byte 65,536: just past the 64 KiB cache every v4 vector allocates

def bind_set(entry, dev, ev, lst, stp, slot, period, prog4, params=()):
    """Mode 27 SET (§6.3): entry devType eventId list u8 · step s8 · slot period u8 · prog u16 · 4 × {kind u8, arg s16}."""
    pr = list(params) + [(0, 0)] * (4 - len(params))
    assert len(pr) == 4 and -128 <= stp <= 127
    return (bytes([27, 0, entry, dev, ev, lst, stp & 0xFF, slot, period]) + u16(prog4)
            + b"".join(bytes([k]) + s16(a) for k, a in pr))
def bind_clear(entry): return bytes([27, 1, entry])                   # §6.3 sub 1: 1 B (0xFF every entry)
def bind_list(lst, items, count, shift, pitch, mark):                 # §6.3 sub 2: 10 B
    return bytes([27, 2, lst]) + u16(items) + u16(count) + s16(shift) + s16(pitch) + bytes([mark])
def commit(slot, S, lst, cursor, prog4, P=()):                        # §6.5 mode 26 sub 3: 16 B
    return bytes([26, 3, slot]) + u16(S) + bytes([lst]) + u16(cursor) + u16(prog4) + _p4(P)
def discard(slot, S, lst, cursor): return bytes([26, 4, slot]) + u16(S) + bytes([lst]) + u16(cursor)          # sub 4: 6 B
def settle(slot, S, lst, cursor, age): return bytes([26, 5, slot]) + u16(S) + bytes([lst]) + u16(cursor) + u16(age)   # sub 5: 8 B
def vscale(off4, x, y, options, Hh): return bytes([28]) + u16(off4) + s16(x) + s16(y) + bytes([options]) + u16(Hh)    # §6.6: 9 B
def vscale_pair(off4, xL, xR, y, options, Hh):                        # §6.6 `0x9C` per lens: 11 B
    return bytes([0x9C]) + u16(off4) + s16(xL) + s16(xR) + s16(y) + bytes([options]) + u16(Hh)
def inp(dev, ev, value=0, only=None):
    d = {"input": [dev, ev, value]}
    if only: d["only"] = only
    return d
def page(on): return {"page": bool(on)}

class Seq:
    """The S a lens takes at each matched event (§6.2 step 4), counted by the vector's author only to address a
    COMMIT, DISCARD or SETTLE — an input of the vector, never an expectation."""
    def __init__(self): self.n = 0
    def fire(self): self.n = (self.n + 1) & 0xFFFF; return self.n

A0, A1 = rect(100, 100, 120, 80), rect(300, 100, 120, 80)   # the bands of lists 0 and 1 (slots 0 and 1)
MEET0 = rect(200, 150, 60, 20)                             # meets A0 and not A1

def c2_fixture():
    """The cache the v4 vectors share: a strip of 8 rows at pitch 10, 8 item records and their table, a press bar,
    the programs Main's shapes stand for (a pan of T 6 and a press of T 3, `C2-decisions.md` B.10), phone programs
    and the malformed ones the refusals name."""
    class F: pass
    f, c = F(), Cache2()
    f.rows_bytes = image2(120, 80, pattern(120, 80, 401)); f.rows = c.add(f.rows_bytes)
    f.items = [c.add(image2(40, 10, pattern(40, 10, 410 + i))) for i in range(8)]
    f.itab = c.add(b"".join(u16(o) for o in f.items))                         # §6.3: count u16 off4 entries
    f.bar = c.add(image2(120, 10, [15] * 1200))
    f.pan0_bytes = prog(track(A0, 2, drawrec(6, f.rows, 100, 0, lvl=(4, 15), pref0=pref(0, F_Y))))   # y = P0
    f.pan0 = c.add(f.pan0_bytes)
    f.pan1 = c.add(prog(track(A1, 3, drawrec(6, f.rows, 300, 0, lvl=(4, 15), pref0=pref(0, F_Y)))))
    f.itemp = c.add(prog(track(A1, 3, drawrec(6, 0, 300, 110, lvl=(4, 15), pref0=pref(0, F_SRC)))))  # src = P0
    f.press0 = c.add(prog(track(A0, 2, drawrec(3, f.bar, 100, 0, lvl=(6, 15), pref0=pref(0, F_Y)))))
    f.ph0 = c.add(prog(track(A0, 7, fillseg(8, 11))))                          # phone programs: slot 0's band,
    f.ph2 = c.add(prog(track(MEET0, 5, fillseg(8, 9))))                        # an area meeting it,
    f.ph3 = c.add(prog(track(rect(0, 300, 60, 20), 6, fillseg(8, 12))))         # one apart
    f.lvlp = c.add(prog(track(A0, 2, drawrec(4, f.rows, 100, 100, pref0=pref(0, F_LVL1)))))   # lvl1 = P0
    f.srcp = c.add(prog(track(A0, 2, drawrec(4, 0, 100, 100, pref0=pref(0, F_SRC), pref1=pref(1, F_LVL1)))))
    f.offl16 = c.add(prog(track(rect(600, 40, 100, 40), 2, drawrec(4, f.rows, 600, 40, lvl=(15, 16)))))
    f.nonel16 = c.add(prog(track(rect(100, 100, 60, 40), 3, drawrec(4, f.rows, 100, 100)),
                           track(rect(250, 100, 60, 40), BD_NONE, drawrec(4, f.rows, 250, 100, lvl=(15, 16)))))
    f.selfbd = c.add(prog(track(rect(0, 300, 40, 20), BD_SELF, wait(4))))
    f.selfdraw = c.add(prog(track(rect(0, 300, 40, 20), 2, drawself(4, 0, 300))))
    f.v2prog = c.add(prog(track(A0, 2, drawrec(4, f.rows, 100, 100)), version=2, bad=True))
    return c, f

def vectors_v4(kf):
    """The C2 set (`FIRMWARE.md` §6.10, spec §4): every vector carries "panel" and features 0x5ff; every input, engine
    message and wake follows a `tick` op in its step. Only `v4-vscale` has a self-test form."""
    v = []
    P = 34                                                        # §6.9's starting P
    full = DRAW2 | ENGINE | BIND
    kf7 = keyframe(pattern(W, H, 7))
    def writes(c): return [msg(m) for m in c.messages()]
    def vec(name, steps): v.append({"name": name, "steps": steps})
    def arm(c, t=1000, frame=True, fl=full):
        return step(tk(t), {"lease": "acquire"}, flags(fl), page(True), *([msg(kf)] if frame else []), *writes(c))
    def up_e(f, e=0, arg=100, lst=0, slot=0, prog4=None):        # a scroll entry: id 4, step +1, y = c' × pitch + arg
        return bind_set(e, ANY, UP, lst, 1, slot, P, f.pan0 if prog4 is None else prog4, [(K_PITCH, arg)])
    def dn_e(f, e=1, arg=100, lst=0, slot=0, prog4=None):        # id 5, step −1
        return bind_set(e, ANY, DOWN, lst, -1, slot, P, f.pan0 if prog4 is None else prog4, [(K_PITCH, arg)])
    def tap_e(prog4, e=2, arg=100): return bind_set(e, RING, TAP, 0, 0, 0, P, prog4, [(K_PITCH, arg)])
    def table(f, t=1010, mark=1): return step(tk(t), msg(bind_list(0, f.itab, 8, 0, 10, mark)), msg(up_e(f)), msg(dn_e(f)))
    def pan_p(cur): return (10 * cur + 100,)                    # the P a COMMIT names for pan0 at a cursor (pitch 10, arg 100)
    def fire_at(t, *ops): return [step(tk(t), *ops), step(tk(t), wake())]   # an input, then the wake that draws step 1
    probe = lambda f, mark=9: msg(bind_list(0, f.itab, 8, 0, 10, mark))     # claims the word: a refusal exit that kept it reads 14 here

    # v4-bind — §6.2 step 2 "BIND armed, `bind_live` set, and an entry matching the record — id 4 or 5 against a scroll
    # entry (devType any), or id 0 with devType 4 against a tap entry — the lowest-numbered matching entry. Otherwise
    # the stock call, no report"; step 3 "The EvenHub page in front. Otherwise the stock call, no report"; step 4 "every
    # matched event on the EvenHub page takes one [S]" (the first fire's S 1 shows that nothing before it took one);
    # §6.4 "it writes nothing into the shadow"; §6.3 SET "Two shapes only: a scroll entry — eventId 4 or 5, devType
    # 0xFF (any), step ≠ 0; a tap entry — eventId 0, devType 4 (the ring), step 0. Anything else is refused 12. A tap
    # entry whose prog is 0xFFFF ... is report-only ...; its period and parameters are checked as any SET's" (D0);
    # "One slot per list: an entry whose list another entry binds to a different slot is refused 12"; the check order
    # "length (1; SET 23 B, CLEAR 3 B, LIST 12 B ...; an unknown sub, 3..255, must be 3 B) → ... → values (12: every
    # range in the table above, the two entry shapes, one slot per list; an unknown sub here) → ... SET: the program
    # record's own structure ... and no track's backdrop or segment `self` (5); ... a scroll entry naming 0xFFFF fails
    # this check (5)"; "A refused mode 27 changes nothing"; "The mode byte's high bit is ignored". The refused
    # scroll-shaped SETs name entries 0..5, below the entries that fire later: one that wrote would take the match.
    c, f = c2_fixture()
    refused = [
        bind_set(0, ANY, DOUBLE, 0, 1, 0, P, f.pan0),            # id 1 as a scroll entry: 12
        bind_set(0, RING, DOUBLE, 0, 0, 0, P, f.press0),         # id 1 as a tap entry: 12
        bind_set(0, RING, LONG, 0, 0, 0, P, f.press0),           # id 3: 12
        bind_set(0, ANY, PRESS, 0, 1, 0, P, f.pan0),             # 0xD: 12
        bind_set(0, RING, RELEASE, 0, 0, 0, P, f.press0),        # 0xE: 12
        bind_set(0, RING, UP, 0, 1, 0, P, f.pan0),               # id 4 with devType 4, not 0xFF: 12
        bind_set(0, TOUCH_L, DOWN, 0, -1, 0, P, f.pan0),         # id 5 with devType 0: 12
        bind_set(0, ANY, UP, 0, 0, 0, P, f.pan0),                # id 4 with step 0: 12
        bind_set(1, TOUCH_R, TAP, 0, 0, 0, P, f.press0),         # id 0 with devType 1: 12
        bind_set(1, ANY, TAP, 0, 0, 0, P, f.press0),             # id 0 with devType 0xFF: 12
        bind_set(1, RING, TAP, 0, 1, 0, P, f.press0),            # id 0 with step 1: 12
        bind_set(8, ANY, UP, 0, 1, 0, P, f.pan0),                # entry 8: 12
        bind_set(0, ANY, UP, 4, 1, 0, P, f.pan0),                # list 4: 12
        bind_set(0, ANY, UP, 0, 1, 4, P, f.pan0),                # slot 4: 12
        bind_set(0, ANY, UP, 0, 1, 0, 15, f.pan0),               # period 15: 12
        bind_set(0, ANY, UP, 0, 1, 0, 129, f.pan0),              # period 129: 12
        bind_set(0, ANY, UP, 0, 1, 0, P, f.pan0, [(K_PITCH, 100), (3, 0)]),   # a kind 3: 12
        bind_set(0, ANY, UP, 0, 1, 1, P, f.pan0),                # list 0, which entry 7 binds to slot 0, in slot 1: 12
        bind_set(1, RING, TAP, 0, 0, 2, P, NONE),                # a tap entry shares its list's slot: 12
        bind_set(0, ANY, UP, 0, 1, 0, P, NONE),                  # a scroll entry naming 0xFFFF: 5 (D0)
        bind_set(2, RING, TAP, 0, 0, 0, 15, NONE),               # report-only, period 15: 12 (D0)
        bind_set(2, RING, TAP, 0, 0, 0, 129, NONE),              # report-only, period 129: 12
        bind_set(2, RING, TAP, 0, 0, 0, P, NONE, [(3, 0)]),      # report-only, a kind 3: 12
        bind_set(0, ANY, UP, 0, 1, 0, P, f.v2prog),              # a program of version 2: 5
        bind_set(0, ANY, UP, 0, 1, 0, P, f.selfbd),              # a track's backdrop `self`: 5
        bind_set(0, ANY, UP, 0, 1, 0, P, f.selfdraw),            # a DRAW of `self`: 5
        bind_set(0, ANY, UP, 0, 1, 0, P, PAST),                # a program past the cache: 5
        bind_set(8, ANY, UP, 0, 1, 0, P, f.v2prog),              # entry 8 and a bad program: 12 before 5
        bad(bind_set(0, ANY, UP, 0, 1, 0, P, f.pan0)[:-1]),      # 22 B: 1
        bad(bind_set(8, ANY, UP, 0, 1, 0, P, f.pan0) + b"\0"),   # 24 B and entry 8: 1 before 12
        bad(bind_clear(0)[:-1]), bad(bind_clear(8) + b"\0"),     # CLEAR of 2 B, of 4 B: 1
        bind_clear(8),                                           # CLEAR entry 8: 12
        bad(bytes([27, 3, 0])), bad(bytes([27, 0x80, 0])),       # unknown subs of 3 B: 12
        bad(bytes([27, 3, 0, 0])), bad(bytes([27, 0xFF])),       # of 4 B, of 2 B: 1
    ]
    st = [arm(c, fl=DRAW2 | ENGINE),                                                       # 0  BIND not armed
          step(tk(1100), inp(RING, UP), inp(TOUCH_L, DOWN, 30), inp(RING, TAP)),           # 1  not armed: the stock call (K 3), no 115
          step(tk(1110), flags(full), inp(RING, UP), inp(TOUCH_R, DOWN, 30), inp(RING, TAP)),   # 2  armed, `bind_live` 0: the same
          step(tk(1120), msg(bind_list(0, f.itab, 8, 0, 10, 1)), msg(dn_e(f, e=7))),       # 3  the table: list 0, one entry (7, id 5)
          step(tk(1130), inp(RING, UP), inp(RING, TAP), inp(TOUCH_R, DOUBLE), inp(RING, LONG), inp(TOUCH_L, TAP))]   # 4  no entry matches: K 5, no 115
    st += [step(tk(1200 + i), msg(m)) for i, m in enumerate(refused)]                     # 5..  each refused, nothing written
    t = 1300
    st += [
        step(tk(t), msg(bytes([0x9B]) + bind_list(0, f.itab, 8, 0, 10, 2)[1:])),           # the mode byte's high bit ignored: a LIST (mark 2)
        step(tk(t + 10), msg(up_e(f, e=6)), msg(tap_e(NONE))),                              # entry 6 (id 4) and the report-only tap entry: taken
        step(tk(t + 20), msg(up_e(f, e=5, lst=1, slot=1)), msg(up_e(f, e=5, lst=1, slot=2))),   # one slot per list counts OTHER entries: a re-SET of the only entry on list 1 to slot 2 is taken
        step(tk(t + 30), msg(bind_clear(5))),
        step(tk(t + 40), page(False), inp(RING, UP), inp(TOUCH_L, DOWN, 30), inp(RING, TAP)),   # a stock page in front: the stock call, no S, no 115
        step(tk(t + 50), page(True), inp(TOUCH_R, UP, 40)),                                # S 1: entry 6 (field 4; devType 1 matches "any"), c' 1
        step(tk(t + 50), wake()),                                                          # step 1 on the panel; the shadow unchanged (L, R as before)
        step(tk(t + 60), msg(up_e(f, e=4, arg=60))),                                       # a lower entry for id 4
        step(tk(t + 70), inp(RING, UP)), step(tk(t + 70), wake()),                         # S 2: the lowest matching entry, 4 (P0 = 2 × 10 + 60)
        step(tk(t + 80), inp(TOUCH_L, DOWN, 30)), step(tk(t + 80), wake()),                # S 3: entry 7 (id 5, step −1), c' 1
        step(tk(t + 90), msg(bind_clear(4))),
        step(tk(t + 100), inp(RING, UP)), step(tk(t + 100), wake()),                       # S 4: entry 6 again, c' 2
        step(tk(t + 110), msg(bind_clear(0xFF))),                                          # every entry; the lists stay
        step(tk(t + 120), inp(RING, UP), inp(TOUCH_L, DOWN, 30), inp(RING, TAP)),          # no entry: the stock call, no 115
        step(tk(t + 130), msg(up_e(f, e=3))), step(tk(t + 140), inp(RING, UP)),             # S 5, c' 3: the list kept its cursor across CLEAR 0xFF
    ]
    vec("v4-bind", st)

    # v4-edge — §6.2 step 9 "The new cursor c' = cursor + step (step 0 for a tap). Outside 0..count−1 → edge (§6.4).
    # The parameters: Pi = arg (kind 0) · the item table's entry at c' + arg (kind 1; no table, or c' + arg outside
    # 0..count−1 → edge) · c' × pitch + arg (kind 2, computed in s32; outside s16 → refused 12)"; §6.4 "The staged
    # edge: ... nothing is drawn (no bounce ...), the cursor stays, the report says `edge`"; §6.5 field 5 "1 edge" and
    # "fields 10–14 only for outcome drew and reason 17". An edge exits before the close set: an open local draws on.
    c, f = c2_fixture()
    st = [arm(c), step(tk(1010), msg(bind_list(0, f.itab, 3, 0, 10, 1)), msg(up_e(f)), msg(dn_e(f)))]
    st += [step(tk(2000), inp(RING, DOWN)), step(tk(2000), wake())]                       # 2  c' −1: edge, the cursor 0 (field 8); nothing drawn
    st += fire_at(2100, inp(RING, UP)) + fire_at(2200, inp(TOUCH_R, UP, 40))              # 4..7  c' 1, c' 2 (the last row)
    st += [step(tk(2250), inp(RING, UP)),                                                 # 8  c' 3 = count: edge, the cursor stays 2
           step(tk(2200 + 2 * P), wake())]                                                # 9  the local of c' 2 draws on: step 3
    # kind 1 (entries 0 and 1 move to list 1, slot 1): the item at c' + arg
    st += [step(tk(3000), msg(bind_list(1, f.itab, 4, 0, 10, 2)),
                msg(bind_set(0, ANY, UP, 1, 1, 1, P, f.itemp, [(K_ITEM, 0)])),
                msg(bind_set(1, ANY, DOWN, 1, -1, 1, P, f.itemp, [(K_ITEM, 2)])))]
    st += fire_at(3100, inp(RING, UP)) + fire_at(3200, inp(RING, UP)) + fire_at(3300, inp(RING, UP))   # c' 1, 2, 3: items 1..3
    st += [step(tk(3400), inp(RING, DOWN)),                                               # c' 2, the item at 2 + 2: past the list: edge, the cursor 3
           step(tk(3500), msg(bind_set(1, ANY, DOWN, 1, -1, 1, P, f.itemp, [(K_ITEM, -3)])), inp(RING, DOWN)),   # c' 2, item −1: edge
           step(tk(3600), msg(bind_set(1, ANY, DOWN, 1, -1, 1, P, f.itemp, [(K_ITEM, 0)])),
                msg(bind_list(1, NONE, 4, 0, 10, 3)), inp(RING, DOWN)),                   # no item table: edge
           step(tk(3650), wake())]                                                        # the local of c' 3 at T (due 3470): the edges closed nothing
    # kind 2 in s32, refused 12 outside s16 (entries 0 and 1 back on list 0, slot 0; pitch ±20000)
    st += [step(tk(4000), msg(bind_list(0, NONE, 8, -2, 20000, 4)), msg(up_e(f, arg=12767)), msg(dn_e(f, arg=7231)))]   # cursor 0
    st += fire_at(4100, inp(RING, UP))                                                    # c' 1: 32,767 drew (cursor 1)
    st += [step(tk(4200), msg(up_e(f, arg=-7232)), inp(RING, UP))]                        # c' 2: 32,768: 12, the cursor stays 1
    st += fire_at(4300, msg(up_e(f, arg=-7233)), inp(RING, UP))                           # c' 2: 32,767 drew (cursor 2)
    st += [step(tk(4400), msg(bind_list(0, NONE, 8, 0, -20000, 5)), msg(up_e(f, arg=27232)), inp(RING, UP)),   # c' 3: −32,768 drew
           step(tk(4500), inp(RING, UP)),                                                 # c' 4: −52,768: 12
           step(tk(4600), inp(RING, DOWN)),                                               # c' 2: −40,000 + 7,231 = −32,769: 12
           step(tk(4700), wake())]
    vec("v4-edge", st)

    # v4-commit — §6.5 COMMIT "PLAY's close pass over the other slots (instances whose areas meet); END(prog, P) into
    # the shadow, always, and RIGHT's CRC of the areas right after it; in its slot: the local instance tagged S becomes
    # ordinary (§6.4), a local one tagged newer ((int16_t)(tag − S) > 0) is left drawing, anything else is closed
    # (outcome 1 if drawing); the list's cursor := COMMIT's cursor only if S is that list's newest fire; one 114
    # outcome 3; presents; a close or an adoption sets the stale mark"; §6.4 "a drawing one runs on to step T and sends
    # its own outcome-0 body; a holding one turns done, no body"; "114 gains outcome 3 ... 6 and 7 the adopted local
    # instance's steps drawn and passed over so far (0 and 0 when none was adopted) ... carrying the COMMIT's CRC"; the
    # check order "the container's list (8) → no shadow (4) → length (1) → the lease (3) → the engine's busy word (14)
    # → DRAW2 (9) → ENGINE (15; BIND is not checked) → values (12: slot 0..3 ...; list 0..3 ...; a named list's cursor
    # below its count) → COMMIT only: the program walk (5; `self` 5) → areas (2) → a `none` backdrop's area (2) → step
    # values (12)". 4 and 14 reach no vector (C1's A.6; nothing holds the word between two host messages): host pins.
    c, f = c2_fixture(); s = Seq()
    st = [arm(c), step(tk(1010), msg(bind_list(0, f.itab, 8, 0, 10, 1)), msg(up_e(f)), msg(dn_e(f)),
                       msg(bind_list(1, f.itab, 8, 0, 10, 1)))]                          # list 1: live, no entry (a COMMIT names it)
    a = s.fire()
    st += fire_at(2000, inp(RING, UP)) + [                                                # S 1, c' 1
        step(tk(2000 + P), wake()),                                                       # step 2
        step(tk(2000 + P + 10), msg(commit(0, a, 0, 1, f.pan0, pan_p(1)))),               # adopts the drawing local: END in the shadow, outcome 3 (6 = 2), the stale mark
        step(tk(2000 + 5 * P), wake())]                                                   # step 6 = T: the adopted instance's outcome 0 (tag 1, ordinal 0), COMMIT's CRC
    b = s.fire()
    st += fire_at(3000, inp(TOUCH_R, UP, 40)) + [                                         # S 2, c' 2
        step(tk(3000 + 5 * P), wake()),                                                   # step T: HOLD, no 114
        step(tk(3000 + 5 * P + 40), msg(commit(0, b, 0, 2, f.pan0, pan_p(2)))),           # a holding local adopted: done, no body; outcome 3 (6 + 7 = T)
        step(tk(3300), wake())]                                                           # nothing drawing: nothing presents
    c3 = s.fire(); s.fire()
    st += fire_at(4000, inp(RING, UP)) + fire_at(4010, inp(RING, UP)) + [                 # S 3 (c' 3), S 4 (c' 4; S 3's local closed silently)
        step(tk(4020), msg(commit(0, c3, 0, 7, f.pan0, pan_p(3)))),                       # older than the newest fire: END written, S 4's local left drawing, the cursor kept (4); 6, 7 = 0, 0
        step(tk(4010 + 2 * P), wake())]                                                   # S 4 draws on: step 3
    d = s.fire()
    st += fire_at(4100, inp(RING, UP)) + [                                                # S 5, c' 5 (field 8: the cursor was kept)
        step(tk(4110), msg(play(2, P, f.ph2, 0x2001))),                                   # a phone PLAY over MEET0: its close pass closes S 5's local (outcome 1, field 8 = 0)
        step(tk(4120), msg(commit(0, d, 0, 5, f.pan0, pan_p(5)))),                        # slot 0 empty: END; the close pass closes slot 2 (outcome 1); S 5 newest: the cursor := 5
        step(tk(4200), msg(play(0, P, f.ph0, 0x2002))),                                   # an ordinary instance in slot 0
        step(tk(4210), msg(commit(0, d, 0, 5, f.pan0, pan_p(5)))),                        # anything else is closed: outcome 1
        step(tk(4300), flags(DRAW2 | ENGINE), msg(commit(0, d, 0, 6, f.pan0, pan_p(6))), flags(full))]   # BIND is not checked: taken, the cursor := 6
    s.fire()
    st += fire_at(4400, inp(RING, UP)) + [                                                # S 6, c' 7
        step(tk(4420), msg(play(1, P, f.ph0, 0x2003)))]                                   # slot 1 over slot 0's band: closes S 6's local (outcome 1)
    st += [step(tk(4500), msg(commit(1, 0x1234, 1, 0, f.pan1, pan_p(0))))]               # a COMMIT into slot 1 for an S never fired: END; its own slot's ordinary closed
    n = s.n
    refusals = [                                                                          # (ops before, the refused message, ops after)
        ([], bad(commit(4, n, 0, 6, NONE)[:-1]), []),                                     # 17 B and slot 4: 1
        ([], bad(commit(4, n, 0, 6, NONE) + b"\0"), []),                                  # 19 B: 1
        ([flags(ENGINE | BIND)], commit(4, n, 0, 6, NONE), [flags(full)]),                 # DRAW2 clear: 9 before 15 and 12
        ([flags(DRAW2 | BIND)], commit(4, n, 0, 6, NONE), [flags(full)]),                  # ENGINE clear: 15 before 12
        ([], commit(4, n, 0, 6, NONE), []),                                               # slot 4: 12 before 5
        ([], commit(0, n, 4, 6, NONE), []),                                               # list 4: 12
        ([], commit(0, n, 0xFF, 6, NONE), []),                                            # list 0xFF (DISCARD's and SETTLE's none): 12
        ([], commit(0, n, 0, 8, NONE), []),                                               # cursor 8 = count: 12
        ([], commit(0, n, 2, 0, f.pan0, pan_p(0)), []),                                   # list 2, not live (count 0): 12
        ([], commit(0, n, 0, 6, NONE), []),                                               # the program past the cache: 5
        ([], commit(0, n, 0, 6, f.selfbd), []),                                           # a `self` backdrop: 5
        ([], commit(0, n, 0, 6, f.offl16), []),                                           # an area off the panel (and level 16): 2 before 12
        ([], commit(0, n, 0, 6, f.nonel16), []),                                          # a `none` backdrop outside track 1 (and level 16): 2
        ([], commit(0, n, 0, 6, f.lvlp, (16,)), []),                                      # level 16 after substitution: 12
    ]
    t = 5000
    for pre, m, post in refusals:
        st.append(step(*pre, tk(t), msg(m), *post, probe(f)))                            # the probe LIST taken: the word was let down
        t += 10
    st += [step(tk(t), {"lease": "release"}, msg(commit(0, n, 0, 6, f.pan0, pan_p(6))))]  # no lease: 3
    vec("v4-commit", st)

    # v4-discard — §6.5 DISCARD "in each named slot, stops every local instance tagged at or after S ((int16_t)(tag −
    # S) ≥ 0), drawing or holding, outcome 1 each (a done one is emptied silently); ordinary instances are untouched;
    # sets the named list's cursor (clearing lost); presents; a stop sets the stale mark"; values "slot 0..3, also 0xFF
    # ...; list 0..3, also 0xFF none ...; a named list's cursor below its count". The first part runs before the
    # session's first Damage frame: (I) a present then takes the copy hook's stock path (§5.3: `direct_active` 0), which
    # turns a drawing local done and leaves its flag (§6.2 step 11) — the one vector route to a done local found under a
    # live lease (report §0).
    c, f = c2_fixture(); s = Seq()
    st = [arm(c, frame=False), step(tk(1010), msg(bind_list(0, f.itab, 8, 0, 10, 1)), msg(up_e(f)),
                                    msg(bind_list(1, f.itab, 8, 4, 10, 1)), msg(dn_e(f, lst=1, slot=1, prog4=f.pan1)))]
    a = s.fire()
    st += fire_at(2000, inp(RING, UP)) + [                                                # S 1; (I) the wake's present takes the stock path: done, no notify
        step(tk(2010), msg(discard(0, a, 0, 0))),                                         # a done local emptied silently (no 114); the cursor := 0
        step(tk(2020), msg(kf))]                                                          # the session's first frame
    b = s.fire()
    st += fire_at(2100, inp(RING, UP)) + [                                                # S 2, c' 1
        step(tk(2110), msg(discard(0, (b + 1) & 0xFFFF, 0, 4))),                          # tag − S = −1: untouched; the cursor := 4 regardless
        step(tk(2120), msg(discard(0, (b - 0x8000) & 0xFFFF, 0, 4))),                     # tag − S = 0x8000 = −32,768: untouched
        step(tk(2100 + 2 * P), wake()),                                                   # S 2 draws on: step 3
        step(tk(2180), msg(discard(0, (b - 11) & 0xFFFF, 0, 4)))]                         # S 0xFFF7, across the wrap: tag − S = 11: stopped (outcome 1)
    c3 = s.fire()
    st += fire_at(2300, inp(RING, UP)) + [step(tk(2310), msg(discard(0, c3, 0, 1)))]      # S 3 (c' 5); S = tag: stopped (the ≥ boundary)
    d = s.fire()
    st += fire_at(2400, inp(RING, UP)) + [step(tk(2410), msg(discard(0, (d - 0x7FFF) & 0xFFFF, 0, 1)))]   # S 4 (c' 2); tag − S = 0x7FFF: stopped
    e5 = s.fire(); s.fire()
    st += fire_at(2600, inp(RING, UP)) + fire_at(2610, inp(TOUCH_L, DOWN, 30)) + [       # S 5 slot 0 (c' 2), S 6 slot 1 (list 1: c' 3)
        step(tk(2610 + 5 * P), wake()),                                                   # both at T: HOLD
        step(tk(2800), msg(discard(0xFF, e5, 0xFF, 0)))]                                  # slot 0xFF: both stopped with their bodies; list none: no cursor set
    s.fire()
    st += [step(tk(2900), msg(play(3, P, f.ph3, 0x3001)))] + fire_at(2910, inp(RING, UP)) + [   # an ordinary instance in slot 3; S 7 (c' 3: the cursor was kept)
        step(tk(2920), msg(discard(0xFF, 1, 0xFF, 0))),                                   # S 7's local stopped; slot 3's ordinary one untouched
        step(tk(2900 + 2 * P), wake())]                                                   # slot 3 draws on
    s.fire(); g = s.fire()
    st += [step(tk(3000), msg(bind_list(0, f.itab, 8, -100, 10, 2))), step(tk(3010), inp(RING, UP)),   # lost; S 8: 18
           step(tk(3020), msg(discard(0, (g + 1) & 0xFFFF, 0, 3)))]                       # the cursor := 3, lost cleared
    st += fire_at(3030, inp(RING, UP))                                                    # S 9: c' 4
    n = s.n
    t = 3100
    for m in [bad(discard(0, n, 0, 1)[:-1]), bad(discard(0, n, 0, 1) + b"\0"),            # 7 B, 9 B: 1
              discard(4, n, 0, 1), discard(0, n, 4, 1), discard(0, n, 0, 8), discard(0, n, 2, 0)]:   # slot 4, list 4, cursor 8, list 2 not live: 12
        st.append(step(tk(t), msg(m), probe(f))); t += 10
    vec("v4-discard", st)

    # v4-settle — §6.5 SETTLE "in each named slot, stops each local instance tagged at or after S that has held its
    # last step for at least `age` OS ticks — d = T and now − (t0 + (T−1)·P) ≥ age as an unsigned 32-bit difference —
    # outcome 1 each; drawing ones, younger ones and ordinary ones are untouched; then sets the named list's cursor
    # (clearing lost) unless a local instance tagged at or after S is still drawing in a named slot after the pass; it
    # presents only when it stopped one (alone, a SETTLE that stopped nothing queues no present, so the image worker
    # marks the panel stale, §4, and the next hinted present goes whole)"; §6.4 "At step T a local instance stays
    # drawing at d = T"; §6.4 "Both lenses ... SETTLE ends it". The clock starts 4,096 ticks before its 32-bit wrap, so
    # held + age wraps before now does (step 26) and now wraps after (step 27).
    c, f = c2_fixture(); s = Seq()
    T0 = 0xFFFFF000
    def tt(d): return (T0 + d) & M32
    st = [arm(c, t=tt(0)), step(tk(tt(10)), msg(bind_list(0, f.itab, 8, 0, 10, 1)), msg(up_e(f)),
                                msg(bind_list(1, f.itab, 8, 4, 10, 1)), msg(dn_e(f, lst=1, slot=1, prog4=f.pan1)))]
    a = s.fire()
    st += fire_at(tt(1000), inp(RING, UP)) + [                                            # S 1, c' 1
        step(tk(tt(1170)), wake()),                                                       # step 6 = T: HOLD from t0 + 5P
        step(tk(tt(1170 + 255)), msg(settle(0, a, 0, 0, 256))),                           # held 255 < 256: untouched; it still draws (HOLD): the cursor kept
        step(tk(tt(1170 + 256)), msg(settle(0, a, 0, 0, 256))),                           # held 256: stopped (outcome 1), the cursor := 0, presents
        step(tk(tt(1500)), msg(settle(0, a, 0, 2, 256))),                                 # nothing to stop: no present; the cursor := 2
        step(tk(tt(1510)), msg(batch(fill(rect(0, 440, 640, 2), 5), hint(440, 441)))),     # whole: the SETTLE that ended without presenting left the panel stale
        step(tk(tt(1520)), msg(batch(fill(rect(0, 300, 8, 8), 9), fill(rect(0, 444, 640, 2), 6), hint(444, 445))))]   # rows only: level 9 stays off the lens
    b = s.fire()
    st += fire_at(tt(2000), inp(RING, UP)) + [                                            # S 2, c' 3 (the cursor was 2)
        step(tk(tt(2010)), msg(settle(0, b, 0, 5, 0))),                                   # drawing (d 1 < T): untouched at age 0; the cursor kept (3)
        step(tk(tt(2170)), wake()),                                                       # step T: HOLD
        step(tk(tt(2180)), msg(settle(0, (b + 1) & 0xFFFF, 0, 5, 0))),                    # tagged below S: untouched, and it does not hold the cursor: := 5
        step(tk(tt(2190)), msg(settle(0, b, 0, 6, 0)))]                                   # held 20 ≥ 0: stopped, the cursor := 6
    st += [step(tk(tt(2300)), msg(bind_list(0, f.itab, 8, -6, 10, 2)))]                   # the cursor 0
    x = s.fire(); s.fire()
    st += fire_at(tt(3000), inp(RING, UP))                                                # S 3 slot 0 (c' 1)
    st += fire_at(tt(3100), inp(TOUCH_L, DOWN, 30)) + [                                   # S 4 slot 1 (list 1, c' 3)
        step(tk(tt(3170)), wake()),                                                       # S 3 at T: HOLD; S 4 at step 3: drawing
        step(tk(tt(3200)), msg(settle(0xFF, x, 0, 4, 0))),                                # slot 0's held: stopped; slot 1's still draws (tag 4 ≥ 3): list 0's cursor kept (1)
        step(tk(tt(3210)), msg(settle(0, x, 0, 4, 0)))]                                   # slot 0 alone: nothing draws there: the cursor := 4; nothing stopped: no present
    y = s.fire()
    st += fire_at(tt(3500), inp(RING, UP)) + [                                            # S 5, c' 5; HOLD from 3670 = 0xFFFFFE56
        step(tk(tt(3670)), wake()),
        step(tk(tt(3670 + 300)), msg(settle(0, y, 0, 0, 0x200))),                         # held 300 < 512 (held + age wraps, now does not): untouched
        step(tk(tt(3670 + 520)), msg(settle(0, y, 0, 0, 0x200)))]                         # now past the wrap, held 520: stopped
    # a LEFT-only input (a dropped answer, §6.1): LEFT one ahead, then a SETTLE from RIGHT's S + 1 realigns it
    r_next = (s.n + 1) & 0xFFFF
    st += [step(tk(tt(5000)), inp(RING, UP, only="L")), step(tk(tt(5000)), wake()),       # LEFT alone: its S 6, its c' 1; RIGHT: K 0, no 115
           step(tk(tt(5170)), wake()),                                                    # LEFT's local at T: HOLD
           step(tk(tt(5200)), msg(settle(0, r_next, 0, 0, 0)))]                           # LEFT's local (tag 6 ≥ 6) stopped, LEFT's cursor := 0; RIGHT: cursor := 0
    st += fire_at(tt(5300), inp(RING, UP))                                                # RIGHT S 6, LEFT S 7: both c' 1, the same row on both panels
    n = s.n + 1
    t = 5400
    for m in [bad(settle(0, n, 0, 1, 0)[:-1]), bad(settle(0, n, 0, 1, 0) + b"\0"),        # 9 B, 11 B: 1
              settle(4, n, 0, 1, 0), settle(0, n, 4, 1, 0), settle(0, n, 0, 8, 0), settle(0, n, 3, 0, 0)]:   # 12
        st.append(step(tk(tt(t)), msg(m), probe(f))); t += 10
    vec("v4-settle", st)

    # v4-list — §6.3 LIST "the staged range, always applied: items, count, pitch and mark replaced; the cursor becomes
    # old + shift when that is inside 0..count−1, else lost (0xFFFF) — old is the list's cursor (0 for a list that is
    # not live, count 0); a lost cursor stays lost. A fire on a lost cursor is refused 18 (§6.2 step 8); DISCARD and
    # SETTLE set an absolute cursor and clear it (§6.5). The newest S is not touched"; "LIST: the item table [items·4,
    # items·4 + 2·count) inside the cache (5)"; count 1..1024 (12); §6.5 field 17 "the list's mark (the last LIST this
    # lens applied)"; "The empty table. `bind_live` ... is 0 ... after every release point ... The first mode 27
    # accepted while `bind_live` is 0 first empties every entry and every list (count 0, cursor 0, mark 0, no newest
    # S), then applies, then sets `bind_live` to 1. A refused mode 27 changes nothing. Arming or disarming BIND clears
    # nothing (the table and S stay ...)".
    c, f = c2_fixture()
    st = [arm(c), step(tk(1010), msg(up_e(f)), msg(dn_e(f)))]                            # entries, no LIST: list 0 not live
    st += [step(tk(1100), inp(RING, UP)),                                                 # S 1: c' 1 outside 0..−1: edge, field 17 = 0
           step(tk(1200), msg(bind_list(0, f.itab, 8, 0, 10, 1)))]                        # a new list from 0: the cursor 0
    st += fire_at(1210, inp(RING, UP))                                                    # S 2: c' 1, field 17 = 1
    st += [step(tk(1300), msg(bind_list(0, f.itab, 8, 2, 10, 2)))]                        # the mark 2, the shift: 1 + 2 = 3
    st += fire_at(1310, inp(RING, UP))                                                    # S 3: c' 4, field 17 = 2
    st += [step(tk(1400), msg(bind_list(0, f.itab, 8, -5, 10, 3))),                       # 4 − 5: lost
           step(tk(1410), inp(RING, UP)),                                                 # S 4: 18, field 8 = 0xFFFF; S 3's local not closed
           step(tk(1500), msg(bind_list(0, f.itab, 8, 5, 10, 4))),                        # a lost cursor stays lost
           step(tk(1510), inp(TOUCH_L, DOWN, 30)),                                        # S 5: 18
           step(tk(1600), msg(discard(0, 6, 0, 2)))]                                      # DISCARD sets 2, clearing lost (S 3's local, tag 3 < 6, untouched)
    st += fire_at(1610, inp(RING, UP))                                                    # S 6: c' 3
    st += [step(tk(1700), msg(bind_list(0, f.itab, 8, 100, 10, 5))),                      # lost
           step(tk(1710), msg(settle(0, 7, 0, 1, 0)))]                                    # SETTLE sets 1 (S 6's local is tagged below 7)
    st += fire_at(1720, inp(RING, UP))                                                    # S 7: c' 2
    st += [step(tk(1800), msg(bind_list(2, f.itab, 4, 5, 10, 6)), msg(bind_list(1, f.itab, 4, 3, 10, 7)),   # not live: 0 + 5 lost; 0 + 3 = 3
                msg(up_e(f, lst=2, slot=2)), msg(dn_e(f, lst=1, slot=1, prog4=f.pan1))),
           step(tk(1810), inp(RING, UP))]                                                 # S 8: list 2 lost at its first LIST: 18
    st += fire_at(1820, inp(RING, DOWN))                                                  # S 9: list 1, c' 2, field 17 = 7
    t = 1900
    for m in [bind_list(0, f.itab, 0, 0, 10, 8), bind_list(0, f.itab, 1025, 0, 10, 8),    # count 0, 1025: 12
              bind_list(4, f.itab, 8, 0, 10, 8),                                          # list 4: 12
              bind_list(0, 0x3FFC, 9, 0, 10, 8),                                          # the table [0xFFF0, 0x10002) past 64 KiB: 5
              bind_list(0, 0x3FFC, 1025, 0, 10, 8),                                       # count 1025 and past the cache: 12 before 5
              bad(bind_list(0, f.itab, 8, 0, 10, 8)[:-1]), bad(bind_list(0, f.itab, 8, 0, 10, 8) + b"\0")]:   # 11 B, 13 B: 1
        st.append(step(tk(t), msg(m))); t += 10
    st += [step(tk(t), msg(bind_list(1, 0x3FFC, 8, 0, 10, 9)), msg(bind_list(3, NONE, 1024, 1, 10, 9)))]   # [0xFFF0, 0x10000) inside; count 1024: taken
    st += fire_at(t + 10, inp(TOUCH_R, UP, 40))                                           # S 10: list 2 still lost: 18 (a refused LIST changed nothing)
    st += fire_at(t + 20, msg(up_e(f)), inp(RING, UP))                                    # S 11: list 0, c' 3 (its cursor 2 kept through the refusals)
    # the empty table after a release point
    rearm = [{"lease": "acquire"}, flags(full), msg(kf), *writes(c)]
    st += [step(tk(3000), {"lease": "release"}, *rearm),                                  # a release point: `bind_live` 0, S 0
           step(tk(3010), inp(RING, UP), inp(RING, DOWN)),                                # `bind_live` 0: the stock call, no 115
           step(tk(3020), msg(bind_list(0, f.itab, 0, 0, 10, 8))),                        # refused (12): changes nothing, `bind_live` stays 0
           step(tk(3030), inp(RING, UP)),                                                 # still the stock call
           step(tk(3040), msg(up_e(f))),                                                  # the first accepted: empties every entry and list, then applies
           step(tk(3050), inp(RING, UP)),                                                 # S 1 (S cleared): list 0 emptied (count 0): edge, field 17 = 0
           step(tk(3060), inp(RING, DOWN)),                                               # entry 1 emptied: the stock call, no 115
           step(tk(3070), msg(bind_list(0, f.itab, 8, 0, 10, 9)))]                        # from cursor 0, not the old 3
    st += fire_at(3080, inp(RING, UP))                                                    # S 2: c' 1
    st += [step(tk(3090), flags(DRAW2 | ENGINE), inp(RING, UP)),                          # BIND disarmed: the stock call, nothing cleared
           step(tk(3100), flags(full))]
    st += fire_at(3110, inp(RING, UP))                                                    # S 3: c' 2 — the table and S stayed
    st += [step(tk(3200), {"lease": "release"}, *rearm),                                  # another release point
           step(tk(3210), msg(bind_list(1, f.itab, 8, 2, 10, 10)), msg(dn_e(f, lst=1, slot=1, prog4=f.pan1)))]   # a LIST first: empties entries too
    st += fire_at(3220, inp(RING, UP)) + fire_at(3230, inp(RING, DOWN))                   # id 4: no entry, the stock call; S 1: list 1 from 0 + 2: c' 1
    vec("v4-list", st)

    # v4-local — §6.4 "one slot of C1's instance table flagged local ... it writes nothing into the shadow"; "HOLD. At
    # step T a local instance stays drawing at d = T and owes no 114: the thread's plan skips it (d ≥ T); the preserve
    # path skips it (k ≤ d) and leaves the framebuffer holding it; the direct path redraws step T at every phone frame's
    # present, so while one is open every phone present goes whole"; "Its 114 bodies carry tag S, END ordinal 0 and
    # field 8 = 0"; "What ends a local instance: ... the next fire in its slot (closed with no body, §6.2 step 11) ...;
    # a phone PLAY or CLOSE of its slot, a live keyframe (mode 6), a cache write meeting its first read range: outcome 1
    # ...; a present on the hook's stock path: turned done, no notify"; §6.2 step 15 "the captured 114 bodies, then the
    # report"; §6.2 step 11 "a local instance of this fire's own slot silently whatever its state".
    c, f = c2_fixture()
    st = [arm(c), table(f)]
    st += fire_at(2000, inp(RING, UP)) + [                                                # S 1: step 1 on the panel; the shadow unchanged
        step(tk(2000 + P), wake()),                                                       # step 2
        step(tk(2000 + 5 * P), wake()),                                                   # step 6 = T: HOLD, no 114
        step(tk(2200), wake()),                                                           # the plan skips a HOLD: nothing presents
        step(tk(2210), stock()),                                                          # the preserve path: k = T ≤ d, nothing drawn
        step(tk(2220), msg(batch(fill(rect(0, 440, 640, 2), 5), hint(440, 441)))),        # a phone frame: the direct path redraws step T, whole
        step(tk(2300), msg(kf7))]                                                         # a live keyframe: outcome 1 (tag 1, ordinal 0, field 8 = 0)
    st += fire_at(2400, inp(RING, UP)) + [step(tk(2410), msg(cache_write2((f.rows, f.rows_bytes))))]   # S 2; a write over its record: outcome 1
    st += fire_at(2500, inp(RING, UP)) + [step(tk(2510), msg(play(0, P, f.ph0, 0x4001)))]               # S 3; a phone PLAY into its slot: outcome 1
    st += [step(tk(2600), msg(play(2, P, f.ph2, 0x4002)))]                                # slot 2 over MEET0 (closes slot 0's PLAY: they meet)
    st += fire_at(2610, inp(RING, UP))                                                    # S 4: closes slot 2's instance: its 114 before the 115
    st += fire_at(2700, inp(RING, UP))                                                    # S 5: closes S 4's local silently: the 115 alone
    st += fire_at(90500, inp(RING, UP)) + [                                               # S 6, the lease (acquired at 1000) still live
        step(tk(91100), msg(copy((0, 440, 8, 2), (8, 440, 8, 2)))),                       # a v1 copy after the unnoticed lapse: the stock path, S 6 done, no notify
        step(tk(91200), wake()),                                                          # nothing drawing: nothing presents
        step(tk(91300), msg(fill(rect(0, 412, 8, 2), 9)))]                                # a v2 op notices the lapse: 3, a release point
    vec("v4-local", st)

    # v4-tap — §6.2 step 2 "id 0 with devType 4 against a tap entry"; step 9 "step 0 for a tap"; step 11 "A tap fire
    # whose close set holds a local instance is refused 17, nothing closed ... a done local refuses a tap fire 17 as a
    # drawing one does"; "A report-only tap entry's fire ... runs steps 1–8 as a tap fire's ... and step 11's test: a
    # local instance in its slot (a done one included) → refused 17. Otherwise it computes no parameters, walks
    # nothing, closes and opens nothing, leaves the list as it is (cursor and newest S) and sets no engine flag ...;
    # it lets the word down and sends its report, outcome 0 with no fields 10–14 (D0)"; §6.4 "The tap press. A tap
    # entry has step 0, so the cursor never moves ... then HOLD"; §6.10 v4-tap's list (D1: "the newest S untouched,
    # read through a COMMIT of the preceding scroll sent after the fire and naming a cursor other than the lens's").
    # The first part runs before the session's first frame, for a done local (I, as v4-discard).
    c, f = c2_fixture(); s = Seq()
    st = [arm(c, frame=False), step(tk(1010), msg(bind_list(0, f.itab, 8, 0, 10, 5)), msg(up_e(f)), msg(tap_e(NONE)))]
    s.fire()
    st += fire_at(2000, inp(RING, UP)) + [                                                # S 1 (c' 1); (I) the stock path turns it done
        step(tk(2010), inp(RING, TAP)),                                                   # S 2 report-only: 17 (a done local in its slot)
        step(tk(2020), msg(tap_e(f.press0)), inp(RING, TAP)),                             # S 3 press entry: 17 (the done local)
        step(tk(2030), msg(kf))]                                                          # the first frame (closes the done local, no notify)
    s.fire(); s.fire(); s.fire()                                                          # S 2, S 3 above; S 4 below
    st += fire_at(2100, inp(RING, TAP)) + [                                               # S 4: a press at the cursor (1), unmoved (field 8 = 1); field 17 = 5
        step(tk(2100 + 2 * P), wake()),                                                   # step 3 = T: HOLD
        step(tk(2200), inp(TOUCH_R, TAP), inp(TOUCH_L, TAP))]                             # temple taps: no entry matches, the stock call, no 115
    sc5 = s.fire(); s.fire()
    st += fire_at(2300, inp(RING, UP)) + [                                                # S 5: a scroll closes the press silently, c' 2
        step(tk(2310), inp(RING, TAP)),                                                   # S 6: 17, nothing closed; fields 10–14 carried
        step(tk(2300 + P), wake()),                                                       # S 5's local draws on
        step(tk(2320 + P), msg(commit(0, sc5, 0, 2, f.pan0, pan_p(2))))]                  # S 5 adopted: ordinary
    pr7 = s.fire()
    st += fire_at(2400, inp(RING, TAP))                                                   # S 7: a press after COMMIT: the ordinary one closed (its 114 first), the press opens
    st += [step(tk(2500), msg(discard(0, pr7, 0, 2)), msg(tap_e(NONE)))]                  # the press stopped; the report-only form
    sc8 = s.fire()
    st += fire_at(2600, inp(RING, UP)) + [step(tk(2610), msg(commit(0, sc8, 0, 3, f.pan0, pan_p(3))))]   # S 8 (c' 3), adopted: ordinary, drawing
    s.fire()
    st += fire_at(2620, inp(RING, TAP)) + [step(tk(2600 + 2 * P), wake())]                # S 9: outcome 0, no 10–14, nothing drawn; the ordinary one draws on, no 114
    sc10 = s.fire(); s.fire(); s.fire()
    st += fire_at(2700, inp(RING, UP)) + [step(tk(2710), inp(RING, TAP)),                 # S 10 (c' 4); S 11: 17 (an uncommitted scroll)
           step(tk(2720), msg(bind_list(0, f.itab, 8, -100, 10, 6))),                     # lost
           step(tk(2730), inp(RING, TAP)),                                                # S 12: 18 before 17
           step(tk(2800), msg(discard(0, sc10, 0, 2)))]                                   # stops S 10's local; the cursor := 2
    sc13 = s.fire(); s.fire(); s.fire()
    st += fire_at(2810, inp(RING, UP)) + [                                                # S 13: c' 3, the list's newest S
        step(tk(2820), msg(close(0))),                                                    # its slot emptied (outcome 1)
        step(tk(2830), inp(RING, TAP)),                                                   # S 14: outcome 0; the newest S stays 13
        step(tk(2840), msg(commit(0, sc13, 0, 6, f.pan0, pan_p(6))))]                     # S 13 still the newest: the cursor := 6 (not the lens's 3)
    st += fire_at(2850, inp(RING, UP))                                                    # S 15: c' 7 shows it
    vec("v4-tap", st)

    # v4-fail — §6.2 step 1 "`peek`; no context → the stock call"; step 5 "The lease ... Lapsed → report refused 3"
    # and §6.5 "refused 3 or 14 ... carry fields 1, 2, 3, 5, 6 and 15 only"; step 7 "DRAW2, ENGINE, BIND and
    # `bind_live` — any clear → refused 15"; step 10 "§5.5's checks from the walk onward ...: the program record and
    # every record and table it names inside the cache and well formed, no `self` ... (5) → areas (2) → a `none`
    # backdrop's area (2) → every step's values after substitution (12 ...)"; §6.3 "SET: the program record's own
    # structure ... What depends on the parameters (the records they name, areas, step values) is checked at every
    # fire"; §6.3 mode 27's order "the container's list (8) → length (1) → the lease (3) → the engine's busy word claimed
    # (14) → DRAW2 (9) → BIND (15) → values (12 ...)"; §6.5 "an unknown sub is now 6..255"; §6.8 "a FLAGS_SET of BIND
    # alone is taken, status 0, and every fire and mode 27 is then refused 15"; "it is refused without the lease (status
    # 3)"; step 4 with §6.3 "`bind_live` ... after every release point ... beside the store that sets S to 0". "The word
    # held → 14" reaches no vector (no op holds the word between host messages): F1's host pin.
    c, f = c2_fixture()
    lapsed = 1000 + 90000 + 10
    st = [step(tk(500), inp(RING, UP)),                                                   # 0  no context yet: the stock call (K 1), nothing else
          arm(c), table(f)]
    st += fire_at(1100, inp(RING, UP))                                                    # S 1: nothing took one at step 0
    st += [step(tk(1200), flags(DRAW2 | BIND), inp(RING, UP)),                            # status 0; S 2: 15 (ENGINE clear), the full body
           step(tk(1210), flags(ENGINE | BIND), inp(RING, UP)),                           # S 3: 15 (DRAW2 clear)
           step(tk(1220), flags(DRAW2 | ENGINE), inp(RING, UP)),                          # BIND clear: no match, the stock call, no S
           step(tk(1230), flags(full)),
           step(tk(1300), msg(cache_write2((f.pan0, bytes([2]) + f.pan0_bytes[1:]))), inp(RING, UP)),   # a rewritten program (version 2): S 4: 5
           step(tk(1310), msg(cache_write2((f.pan0, f.pan0_bytes))))]
    t = 1400
    for e in [bind_set(0, ANY, UP, 0, 1, 0, P, f.offl16, [(K_PITCH, 100)]),               # S 5: an area off the panel (and level 16): 2 before 12
              bind_set(0, ANY, UP, 0, 1, 0, P, f.nonel16, [(K_PITCH, 100)]),              # S 6: a `none` backdrop outside track 1: 2
              bind_set(0, ANY, UP, 0, 1, 0, P, f.lvlp, [(K_ARG, 16)]),                    # S 7: level 16 after substitution: 12
              bind_set(0, ANY, UP, 0, 1, 0, P, f.srcp, [(K_ARG, PAST), (K_ARG, 16)]),   # S 8: a record past the cache (and level 16): 5 before 12
              bind_set(0, ANY, UP, 0, 1, 0, P, f.lvlp, [(K_ARG, 15)])]:                   # S 9: level 15: drew
        st += [step(tk(t), msg(e), inp(RING, UP))]; t += 10
    st += [step(tk(t), wake()), step(tk(1500), msg(up_e(f)))]
    t = 1600
    pairs = [([msg(bytes([16, 0]))], bytes([16, 1]) + bad(bind_list(0, f.itab, 0, 0, 10, 1)[:-1]), [msg(bytes([16, 2]))]),   # in a step, 11 B and count 0: 8
             ([msg(bytes([16, 0]))], bytes([16, 1]) + batch(fill(rect(0, 300, 8, 8), 3), bind_list(0, f.itab, 8, 0, 10, 1)),
              [msg(bytes([16, 2]))]),                                                     # in a batch the step carries: 8
             ([], bad(bind_list(4, f.itab, 0, 0, 10, 1)[:-1]), []),                       # 11 B, list 4: 1 before 12
             ([flags(ENGINE | BIND)], bind_list(4, f.itab, 0, 0, 10, 1), [flags(full)]),   # DRAW2 clear: 9 before 15, 12
             ([flags(DRAW2 | ENGINE)], bind_list(4, f.itab, 0, 0, 10, 1), [flags(full)]),  # BIND clear: 15 before 12
             ([flags(DRAW2 | BIND)], bind_list(4, f.itab, 0, 0, 10, 1), [flags(full)]),    # ENGINE clear, BIND set (report §0: §6.8 reads 15)
             ([], bad(bytes([26, 3, 0])), []),                                            # sub 3 is COMMIT here: 3 B: 1 (from v3-rulings, B.2)
             ([], bad(bytes([26, 4, 0])), []),                                            # DISCARD of 3 B: 1
             ([], bad(bytes([26, 6, 0])), []),                                            # an unknown sub is 6..255: 12, no slot in the body
             ([], bad(bytes([26, 0xFF, 0])), [])]                                         # 12
    for pre, m, post in pairs:
        st.append(step(*pre, tk(t), msg(m), *post)); t += 10
    st += [step(tk(lapsed), inp(RING, UP)),                                               # S 10 then the lapse: 3 carrying S 10 (fields 1, 2, 3, 5, 6, 15); a release point
           step(tk(lapsed + 10), msg(bind_list(4, f.itab, 0, 0, 10, 1)), flags(full)),     # no lease: 3 before 12; FLAGS_SET: status 3
           step(tk(lapsed + 20), {"lease": "acquire"}, flags(full), msg(kf), *writes(c), inp(RING, UP)),   # `bind_live` 0: the stock call, no S
           step(tk(lapsed + 30), msg(bind_list(0, f.itab, 8, 0, 10, 2)), msg(up_e(f)))]
    st += fire_at(lapsed + 40, inp(RING, UP))                                             # S 1: S was cleared with `bind_live`
    vec("v4-fail", st)

    # v4-lens — §6.4 "Both lenses. LEFT fires about one inter-lens answer before RIGHT ... A lost answer leaves a fire on
    # LEFT that RIGHT and the phone never hear of: LEFT's cursor one ahead and its S one ahead for the rest of the lease
    # ... SETTLE ends it (§6.5)"; §6.2 step 4 "both lenses number every matched event alike while their tables agree";
    # §6.5 "On the glasses COMMIT, DISCARD and SETTLE are keyed by S, so the two lenses applying one on different sides
    # of a fire reach the same shadow, and DISCARD's and SETTLE's absolute cursor realigns them". (S as RIGHT counts it.)
    c, f = c2_fixture()
    st = [arm(c), table(f)]
    st += fire_at(2000, inp(RING, UP))                                                    # S 1 on both, c' 1 on both
    st += fire_at(2100, inp(RING, UP, only="L"))                                          # LEFT alone: S 2, c' 2; RIGHT: K 0, no 115
    st += fire_at(2200, inp(RING, UP)) + [                                                # RIGHT S 2 (c' 2), LEFT S 3 (c' 3)
        step(tk(2210), msg(commit(0, 2, 0, 2, f.pan0, pan_p(2)))),                        # RIGHT adopts; LEFT's tag 3 > 2 left drawing, its cursor kept (3); END on both
        step(tk(2200 + 5 * P), wake()),                                                   # RIGHT's adopted at T (outcome 0); LEFT's local at T: HOLD
        step(tk(2400), msg(settle(0, 3, 0, 2, 0)))]                                       # S_low 3: LEFT's local stopped, both cursors := 2
    st += fire_at(2500, inp(RING, UP)) + [                                                # RIGHT S 3, LEFT S 4: both c' 3, one row on both panels
        step(tk(2510), msg(discard(0, 3, 0, 3)))]                                         # both stopped (LEFT's tag 4 ≥ 3); both on row 3
    st += fire_at(2600, inp(TOUCH_L, DOWN, 30)) + fire_at(2610, inp(RING, UP))            # RIGHT S 4, 5; LEFT S 5, 6: both c' 2, then 3
    vec("v4-lens", st)

    # v4-batch — §6.5 "Batch order. ... a refused sub-message ends its batch with no present (§4 ...), so a refusal of
    # theirs loses only the batch's present ... and never the frame, which is then in both shadows ... Because END lands
    # after the frame ..."; §6.3 "alone or as a mode-8 sub-message ... Not gated ... (in a batch the batch's present is
    # the batch's)"; "A refusal of mode 27 sends no notify".
    c, f = c2_fixture()
    st = [arm(c), table(f)]
    st += fire_at(2000, inp(RING, UP)) + [                                                # S 1
        step(tk(2010), msg(batch(fill(rect(0, 400, 640, 4), 6), discard(4, 1, 0, 0)))),    # DISCARD refused (slot 4): the fill in both shadows, no present, the local untouched
        step(tk(2000 + P), wake()),                                                       # S 1 draws on (the fill reaches the lens with this whole present)
        step(tk(2050), msg(batch(fill(rect(0, 404, 640, 4), 7), discard(0, 1, 0, 0)))),    # accepted after the frame: S 1 stopped, one present
        step(tk(2060), msg(batch(fill(rect(0, 408, 640, 4), 8), bind_list(0, f.itab, 8, 1, 10, 2), up_e(f)))),   # mode 27 after the drawing subs: taken, one present
        step(tk(2070), msg(batch(fill(rect(0, 412, 640, 4), 9), bind_list(0, f.itab, 0, 0, 10, 3))))]   # refused (count 0): the fill in the shadow, no present, no notify
    st += fire_at(2100, inp(RING, UP)) + [                                                # S 2: c' 2 (field 17 = 2: the refused LIST changed nothing)
        step(tk(2110), msg(batch(fill(rect(0, 416, 640, 4), 10), commit(0, 2, 0, 2, f.pan0, pan_p(2)))))]   # COMMIT after a frame elsewhere: END, one present
    st += fire_at(2200, inp(RING, UP)) + [                                                # S 3
        step(tk(2210), msg(batch(fill(rect(110, 110, 40, 20), 3), commit(0, 3, 0, 3, f.pan0, pan_p(3)))))]   # a frame inside the slot's band: END lands over it
    st += [step(tk(2300), msg(batch(fill(rect(0, 420, 640, 4), 11), settle(0xFF, 4, 0xFF, 0, 0))))]   # a SETTLE that stops nothing: the batch presents
    vec("v4-batch", st)

    # v4-vscale — §6.6 "Mode 28 (`0x9C` per lens): off4 u16 · x s16 (or xL · xR) · y s16 · options u8 · H u16 (9 B, or 11
    # B per lens) — the v2 record drawn H rows tall (1..2048): output row r takes source row ⌊(2r + 1)·h / (2H)⌋ ...,
    # columns as they are; clip, options and transparency as mode 17 ... Order: no shadow (4) → length (1) → the lease
    # (3) → DRAW2 (9) → H (12) → the record (5). Allowed in a batch and in a self-test step." No lease op after the
    # first and no engine op, so the self-test form runs it (in a step); 3 and 9 are in `v4-vscale-draw`, 4 is host only.
    c = Cache2()
    r30 = c.add(image2(40, 30, pattern(40, 30, 451)))
    tall = c.add(image2(8, 200, runs_pattern(8, 200, 452)))
    holes = c.add(image2(20, 10, [0 if (i % 7) < 3 else 12 for i in range(200)]))
    st = [step(tk(1000), {"lease": "acquire"}, flags(DRAW2), msg(kf), *writes(c))]
    for m in [vscale(r30, 10, 10, 0x0F, 1),                                               # H 1: the source's middle row
              vscale(r30, 60, 10, 0x0F, 30),                                              # H = h
              vscale(r30, 110, 10, 0x0F, 60),                                             # H = 2h
              vscale(r30, 160, 10, 0x0F, 7),                                              # H 7: the rounding of the row map
              vscale(r30, 210, 100, 0x0F, 2048),                                          # H 2048: clipped at the bottom
              vscale(tall, 260, 0, 0x0F, 400),                                            # (2r + 1)·h past 16 bits
              vscale_pair(r30, 300, 316, 50, 0x0F, 45),                                   # per lens
              vscale(r30, -20, -10, 0x0F, 50), vscale(r30, 620, 470, 0x0F, 50),           # clipped at the panel's four edges
              vscale(holes, 360, 50, 0x1A, 25), vscale(holes, 390, 50, 0x27, 25),         # transparent at level 10; inverse at 7
              batch(fill(rect(420, 0, 100, 100), 3), clip(rect(430, 10, 50, 50)), vscale(r30, 420, 0, 0x0F, 90), hint(0, 99)),   # in a batch, clipped
              batch(fill(rect(530, 0, 100, 100), 4), vscale_pair(r30, 530, 546, 20, 0x0C, 15)),
              bad(vscale(r30, 0, 0, 0x0F, 30)[:-1]), bad(vscale(r30, 0, 0, 0x0F, 30) + b"\0"),   # 9 B, 11 B: 1
              bad(vscale_pair(r30, 0, 0, 0, 0x0F, 30)[:-1]),                              # 0x9C of 11 B: 1
              vscale(PAST, 0, 0, 0x0F, 0), vscale(r30, 0, 0, 0x0F, 2049),               # H 0 (and a record past the cache): 12 before 5; H 2049: 12
              vscale(PAST, 0, 0, 0x0F, 30),                                             # a record past the cache: 5
              batch(fill(rect(0, 460, 640, 4), 5), vscale(r30, 0, 0, 0x0F, 0))]:           # refused in a batch: the fill in the shadow, no present
        st.append(step(msg(m)))
    vec("v4-vscale", st)

    # v4-vscale-draw — §6.6 "DRAW's h0, h1, anchor (zero in C1, §5.4): the output height eased from h0 to h1 (both
    # 1..2048, or both 0 = unscaled); anchor 0 top · 1 centre (y + ((h − H) >> 1)) · 2 bottom (y + h − H), h the source's
    # height. On a bit-10 build §5.5's 'h0/h1 = 0' becomes: h0 and h1 both 0, or every step's eased H (after
    # substitution) inside 1..2048, else 12 — at a PLAY, a COMMIT and a fire alike; on RETARGET's restart path H is
    # clamped to 1..2048 as levels are (R1.10)"; §6.10 "an anchor outside the build's range is refused 5"; the two
    # bit-10 answers moved from v3 (B.2: `v3-params`' P = (0, 2) and `v3-order`'s anchor 1); mode 28's 9 and 3.
    c = Cache2()
    pic = c.add(image2(40, 30, pattern(40, 30, 461)))
    ov = c.add(easing([900, 2300, 3500, 4300, 4620, 4600, 4420, 4096]))                   # an overshoot past E 4096
    sc = [c.add(prog(track(rect(20 + 150 * k, 40, 120, 120), 2, drawrec(4, pic, 30 + 150 * k, 60, h0=10, h1=60, anchor=k))))
          for k in range(3)]                                                              # anchors 0, 1, 2: 10 → 60 rows
    sc_out = c.add(prog(track(rect(470, 40, 120, 120), 2, drawrec(6, pic, 480, 60, ease=EASE_OUT, h0=30, h1=1, anchor=1))))
    sc_un = c.add(prog(track(rect(20, 200, 120, 60), 2, drawrec(4, pic, 30, 210, anchor=2))))       # h0 = h1 = 0: unscaled, anchor taken
    v3p = c.add(prog(track(rect(0, 280, 300, 60), 8, drawrec(4, pic, 10, 290, dx=50, pref0=pref(0, F_H0), pref1=pref(1, F_H1)))))
    v3a = c.add(prog(track(rect(170, 200, 100, 40), 2, drawrec(4, pic, 180, 205, anchor=1))))        # `v3-order`'s anchor 1, on the panel
    an3 = c.add(prog(track(rect(300, 200, 100, 40), 2, drawrec(4, pic, 310, 205, anchor=3)), bad=True))   # anchor 3: 5
    hp = c.add(prog(track(rect(320, 280, 300, 120), 4, drawrec(6, pic, 330, 290, pref0=pref(0, F_H0), pref1=pref(1, F_H1)))))
    rt = c.add(prog(track(rect(20, 300, 120, 160), 4, drawrec(8, pic, 30, 300, ease=TABLE, table=ov, pref0=pref(0, F_H0), pref1=pref(1, F_H1)))))
    bads = [c.add(prog(track(rect(400, 200, 100, 40), 2, drawrec(4, pic, 410, 205, h0=h0, h1=h1)))) for h0, h1 in
            [(0, 3000), (0, 1), (1, 0), (2049, 2049), (0, 2048)]]                          # 12, 12, 12, 12; (0, 2048): H 512.. — taken
    st = [step(tk(1000), {"lease": "acquire"}, flags(full), page(True), msg(kf), *writes(c))]
    t = 2000
    for k, p_ in enumerate(sc + [sc_out, sc_un]):
        st += [step(tk(t), msg(play(k % 4, P, p_, 0x5001 + k))), step(tk(t + P), wake()), step(tk(t + 5 * P), wake())]
        t += 300
    st += [step(tk(t), msg(play(0, P, v3p, 0x5010, P=(0, 2)))),                          # h0 0, h1 2 over 4 linear steps: H 1, 1, 2, 2: taken
           step(tk(t + 10), msg(play(1, P, v3a, 0x5011))),                                # anchor 1, unscaled: taken
           step(tk(t + 20), msg(play(2, P, an3, 0x5012)))]                                # anchor 3: 5
    t += 100
    for k, b_ in enumerate(bads):
        st.append(step(tk(t + 10 * k), msg(play(3, P, b_, 0x5020 + k))))
    t += 100
    # the H rule at a COMMIT and at a fire (list 0 bound to `hp`: h0 = P0, h1 = P1)
    st += [step(tk(t), msg(bind_list(0, NONE, 8, 0, 100, 1)),
                msg(bind_set(0, ANY, UP, 0, 1, 0, P, hp, [(K_ARG, 30), (K_PITCH, 0)])),    # h1 = c' × 100: c' 1 → 100
                msg(bind_set(1, ANY, DOWN, 0, -1, 0, P, hp, [(K_ARG, 3000), (K_ARG, 3000)])))]   # H 3000
    st += fire_at(t + 10, inp(RING, UP))                                                  # S 1: H 30 → 100, drew
    st += [step(tk(t + 20), msg(commit(0, 1, 0, 1, hp, (3000, 3000)))),                   # the H rule at a COMMIT: 12
           step(tk(t + 30), msg(commit(0, 1, 0, 1, hp, (30, 100)))),                      # taken: adopted
           step(tk(t + 40), inp(RING, DOWN)),                                             # S 2: the H rule at a fire: 12
           step(tk(t + 50), msg(play(1, P, hp, 0x5030, P=(0, 3000))))]                    # and at a PLAY: 12
    t += 400
    # RETARGET's restart path clamps H to 1..2048 (the overshoot table: 100 → 2040 passes 2048; 2040 → 5 passes 1)
    st += [step(tk(t), msg(play(2, P, rt, 0x5040, P=(100, 100)))), step(tk(t + P), wake()),
           step(tk(t + 2 * P), msg(retarget(2, (2040, 2040)))),
           step(tk(t + 6 * P), wake()), step(tk(t + 9 * P), wake()),
           step(tk(t + 10 * P), msg(retarget(2, (5, 5)))),
           step(tk(t + 14 * P), wake()), step(tk(t + 17 * P), wake())]
    t += 1000
    st += [step(tk(t), flags(ENGINE | BIND), msg(vscale(PAST, 0, 0, 0x0F, 0)), flags(full)),   # mode 28 with DRAW2 clear: 9 before 12
           step(tk(t + 10), {"lease": "release"}, msg(vscale(PAST, 0, 0, 0x0F, 0)))]          # no lease: 3 before 12
    vec("v4-vscale-draw", st)

    # v4-scaled-self — §6.6 "a scaled DRAW's source is a record — `self` with h0/h1 ≠ 0 is 12 (ruled on the fork's first
    # review pass: no use asks for a scaled capture, and no vector compared one) — at a PLAY, a COMMIT and a fire alike"
    # (C2-decisions M.2), at the place each check order puts step values: §5.5 "→ every step's values in range after
    # substitution ... (12) → for a PLAY with a track whose backdrop or any segment is `self`, the capture range ... (5)"
    # — and RETARGET runs the same checks from the walk on with its new parameters; §6.5 COMMIT "the program walk (5;
    # `self` 5) → areas (2) → a `none` backdrop's area (2) → step values (12)"; §6.2 step 10 "no `self` source or
    # backdrop (5) → areas (2) → ... → every step's values after substitution (12; with §6.6's H rule)". So at a COMMIT
    # and a fire the walk's `self` 5 comes before the values: those steps show which reason the order gives. The
    # look-alike that stays taken: the same DRAW unscaled (§6.6 "both 0 = unscaled"). A new vector, after DX: no
    # earlier file changes.
    c = Cache2()
    pic = c.add(image2(40, 30, pattern(40, 30, 471)))
    SA, SB = rect(100, 100, 60, 40), rect(300, 100, 60, 40)                               # the two `self` areas (slots 0, 1)
    ss_b = prog(track(SA, 2, drawself(4, 100, 100, lvl=(4, 15), h0=10, h1=30)))           # scaled: H 10 → 30 (all in range)
    su_b = prog(track(SA, 2, drawself(4, 100, 100, lvl=(4, 15))))                         # the same DRAW unscaled
    ss, su = c.add(ss_b), c.add(su_b)
    sp = c.add(prog(track(SB, 2, drawself(4, 300, 100, lvl=(4, 15), pref0=pref(0, F_H0), pref1=pref(1, F_H1)))))   # h0 = P0, h1 = P1
    rp_b = prog(track(rect(100, 300, 120, 60), 2, drawrec(4, pic, 100, 0, pref0=pref(0, F_Y))))   # a record DRAW, y = P0
    rp = c.add(rp_b)
    assert len(rp_b) == len(ss_b) == len(su_b)                                            # the fire's record is overwritten in place
    cap0, cap1 = c.reserve(cap_total((60, 40))), c.reserve(cap_total((60, 40)))
    st = [step(tk(1000), {"lease": "acquire"}, flags(full), page(True), msg(kf), *writes(c))]
    t = 2000
    st += [step(tk(t), msg(play(0, P, ss, 0x6001, cap0))),                                # §6.6 at a PLAY: `self` with h0/h1 ≠ 0 → 12
           step(tk(t + 10), msg(play(0, P, ss, 0x6002, PAST))),                           # §5.5: the values (12) before the capture range (5): 12
           step(tk(t + 20), msg(play(0, P, su, 0x6003, PAST))),                           # the look-alike reaches the capture range: 5
           step(tk(t + 30), msg(play(0, P, su, 0x6004, cap0))),                           # the look-alike, unscaled ("both 0"): taken, step 1 drawn
           step(tk(t + 30 + P), wake()), step(tk(t + 30 + 3 * P), wake())]               # steps 2, 4 = T of the capture
    t = 2400
    st += [step(tk(t), msg(play(1, P, sp, 0x6010, cap1, (10, 30)))),                      # §6.6 "(after substitution)": scaled by P0, P1 → 12
           step(tk(t + 10), msg(play(1, P, sp, 0x6011, cap1, (0, 0)))),                   # the same program, unscaled by P: taken
           step(tk(t + 10 + P), wake()),
           step(tk(t + 20 + P), msg(retarget(1, (10, 30)))),                              # §5.5's checks at a RETARGET, new P scaling `self` → 12
           step(tk(t + 30 + P), msg(retarget(1, (0, 0)))),                                # the look-alike RETARGET: taken, restarted
           step(tk(t + 30 + 2 * P), wake())]
    t = 3000
    st += [step(tk(t), msg(bind_list(0, NONE, 8, 0, 10, 1)),                              # list 0 live (cursor 0), slot 2, prog rp:
                msg(bind_set(0, ANY, UP, 0, 1, 2, P, rp, [(K_PITCH, 300)]))),             # y = c' × 10 + 300 (the SET walks rp: no `self`)
           step(tk(t + 10), msg(commit(2, 1, 0, 0, ss))),                                 # §6.5 at a COMMIT: the walk's `self` 5 before step values
           step(tk(t + 20), msg(commit(2, 1, 0, 0, su))),                                 # the look-alike at a COMMIT: 5 as well (any `self`)
           step(tk(t + 30), msg(commit(2, 1, 0, 0, rp, (310,))))]                         # a record program: taken (END, outcome 3; no newest S: the cursor stays 0)
    t = 3200
    st += [step(tk(t), msg(cache_write2((rp, ss_b))), inp(RING, UP)),                     # §6.2 step 10 at a fire (S 1): rp's bytes now the scaled `self`: 5
           step(tk(t + 10), msg(cache_write2((rp, su_b))), inp(RING, UP)),                # the look-alike at a fire (S 2): 5 as well
           step(tk(t + 20), msg(cache_write2((rp, rp_b))), inp(RING, UP)),                # the record program back (S 3): drew, c' 1
           step(tk(t + 20), wake())]                                                      # step 1 of the local
    vec("v4-scaled-self", st)

    # v4-param-order — §6.2 step 9 "The parameters: Pi = arg (kind 0) · the item table's entry at c' + arg (kind 1; no
    # table, or c' + arg outside 0..count−1 → edge) · c' × pitch + arg (kind 2, computed in s32; outside s16 → refused
    # 12). The parameters are evaluated in index order, and the first that gives an edge or 12 decides the fire"
    # (C2-decisions G.3); §6.5 field 5 "1 edge", field 6 "reason (outcome 2)", "fields 10–14 only for outcome drew and
    # reason 17". The cursor stays at 0 through every edge and 12 (§6.4 "the cursor stays"), so each scroll fire has
    # c' 1: kind 1 with arg 10 names item 11 of 4 (edge), kind 2 with arg 32767 gives 10 + 32767 (12); arg 0 is inside
    # either way. The program names no parameter, so only step 9 decides. A new vector, after DX.
    c = Cache2()
    pic = c.add(image2(40, 30, pattern(40, 30, 481)))
    itab = c.add(b"".join(u16(pic) for _ in range(4)))                                   # §6.3: count u16 off4 entries
    op_ = c.add(prog(track(rect(100, 300, 120, 60), 2, drawrec(4, pic, 100, 310))))       # no parameter in the program
    E, T12, OK1, OK2 = (K_ITEM, 10), (K_PITCH, 32767), (K_ITEM, 0), (K_PITCH, 0)          # an edge, a 12, and each kind inside
    def up(*params): return msg(bind_set(0, ANY, UP, 0, 1, 0, P, op_, list(params)))
    def tap(*params): return msg(bind_set(2, RING, TAP, 0, 0, 0, P, op_, list(params)))
    st = [step(tk(1000), {"lease": "acquire"}, flags(full), page(True), msg(kf), *writes(c)),
          step(tk(1010), msg(bind_list(0, itab, 4, 0, 10, 1)))]                           # list 0: 4 items, pitch 10, cursor 0
    t = 2000
    for k, (params, why) in enumerate([
            ((E, T12), "P0 an edge, P1 a 12: the first decides — edge"),
            ((T12, E), "P0 a 12, P1 an edge: the first decides — 12"),
            ((E, OK2), "the look-alike: only the edge (P1 inside) — edge"),
            ((OK1, T12), "the look-alike: only the 12 (P0 inside) — 12"),
            (((K_ARG, 7), (K_ARG, -7), E, T12), "the pair at P2, P3 behind two kind-0 values: edge"),
            (((K_ARG, 7), (K_ARG, -7), T12, E), "the pair at P2, P3 reversed: 12"),
            ((OK1, E, OK2, T12), "an edge at P1, a 12 at P3, inside values around them: edge"),
            ((OK2, T12, OK1, E), "a 12 at P1, an edge at P3: 12")]):
        st.append(step(tk(t + 10 * k), up(*params), inp(RING, UP)))                      # step 9's index order, as `why` says
    t = 2200
    st += [step(tk(t), msg(bind_list(0, NONE, 4, 0, 10, 2))),                             # no item table: kind 1 is an edge whatever its arg
           step(tk(t + 10), up(OK1, T12), inp(RING, UP)),                                 # "no table ... → edge" at P0 before P1's 12: edge
           step(tk(t + 20), up(T12, OK1), inp(RING, UP)),                                 # P0's 12 before P1's no-table edge: 12
           step(tk(t + 30), msg(bind_list(0, itab, 4, 1, 10, 3))),                        # the table back, the cursor 0 + 1 = 1 (§6.3 LIST's shift)
           step(tk(t + 40), tap(E, T12), inp(RING, TAP)),                                 # a tap (step 0, c' 1): P0's edge before P1's 12 — edge
           step(tk(t + 50), tap(T12, E), inp(RING, TAP)),                                 # a tap: P0's 12 before P1's edge — 12
           step(tk(t + 60), up(OK1, OK2), inp(RING, UP)),                                 # neither (c' 2: item 2, 20): drew, P0..P3 in fields 10–13
           step(tk(t + 60), wake())]                                                      # step 1 of the local
    vec("v4-param-order", st)

    for vec_ in v:
        vec_["contract"] = 2
        vec_["start"] = "zero"
        vec_["panel"] = True
        vec_["features"] = [FEATURES_C2, FEATURES_C3]                  # C3 E.4 A16 (§7.13): build-neutral on a bit-11 build
    return v

# ---- C3: effects (`FIRMWARE.md` §7; build C3's spec §3 DV and §4, `C3-decisions.md` A–G) -----------------------------
# Written from §7.2–§7.12 alone, with §5 and §6 where §7 extends them and §9's form. The v5 set adds no op: every v5
# vector carries "features": [0xdff], "panel": true and, per step, the C's "N", "W", "G" and "K" (spec §4). Two vectors
# that share a "pair" key hold the same messages under two wake schedules drawing different steps between the same two
# steps — §7.2 "The base", the fold invariant: both harnesses compare the pair's final "P" (spec §3 DV, §5).
EFFECTS = 0x0040                                   # §7.9 flag bit 6
FULL5 = DRAW2 | ENGINE | BIND | ZWRITE | EFFECTS   # 0x7c, ladder stage 5's flags (§7.9)
PIECES, SHRINK, GLYPHS = 4, 5, 6                   # §7.2 verbs
K_OLD, K_NEW = 0, 4                                # §7.2: PIECES kind 0 old (in place) · kind 4 `new` (DRAW, REVEAL, PIECES)
CELLS, RAIN = 0, 1                                 # §7.2 GLYPHS kinds
BD_NEW = 0x21                                      # §7.2 backdrop `new`
OWN, LAND = 0x01, 0x02                             # §7.2 header flags
F_W0, F_W1, F_AUX0, F_AUX1 = 14, 15, 16, 17        # §7.2 pref codes 14..17 of a version-2 record
K_CUR = 3                                          # §7.8 SET's kind 3: cursor + arg, the cursor before the move
UNIFORM, RADIAL, RANDOM = 0, 1, 2                  # §7.4 model = aux1 & 3
CAP = 8_000_000                                    # §7.12 the step budget's cap, in u

def anc(v=0, h=0): return v | h << 2               # §7.2 anchor: bits 0-1 vertical (0 top · 1 centre · 2 bottom), 2-3 horizontal
def seg2(verb, steps, w0=0, w1=0, aux0=0, aux1=0, **kw):
    """One 40-byte version-2 segment (§7.2): §5.4's 32 B, then w0 w1 aux0 aux1 u16 (PIECES reads w0, w1 as s16)."""
    return seg(verb, steps, **kw) + b"".join(u16(x & 0xFFFF) for x in (w0, w1, aux0, aux1))
def w2(n, **kw): return seg2(WAIT, n, **kw)
def f2(n, level, **kw): return seg2(FILL, n, kind=level, **kw)
def d2(n, src, x, y, dx=0, dy=0, lvl=(15, 15), opt=0, kind=K_RECORD, xR=None, dxR=None, **kw):
    """DRAW v2 (§7.5): a record (kind 0), `self` (2) or `new` (4); w0 w1 (the width), h0 h1, anchor, aux0 (the source
    window's first row) and aux1 (its height) through **kw."""
    return seg2(DRAW, n, kind=kind, src=src, xL=x, xR=xR or 0, y=y, dxL=dx, dxR=dxR or 0, dy=dy,
                lvl0=lvl[0], lvl1=lvl[1], opt=opt, **kw)
def dnew(n, x, y, **kw): return d2(n, 0, x, y, kind=K_NEW, **kw)
def r2(n, edge, kind, x, y, src=0, w=0, h=0, xR=None, **kw):
    """REVEAL v2: a record, `self`, a level (src; its width in dxL, its height in dy) or `new` (kind 4: the area's size)."""
    return seg2(REVEAL, n, kind=kind, src=src, xL=x, xR=xR or 0, y=y, dxL=w, dy=h, opt=edge, **kw)
def pc(n, kind, grid=(1, 1), model=UNIFORM, stagger=0, x=0, y=0, dx=0, dy=0, xR=None, dxR=None, lvl=(15, 15), seed=0,
       f=(0, 4096), vacate=0, opt=0, **kw):
    """PIECES (§7.4): aux0 = gw | gh << 8, aux1 = model | stagger << 8, w0 = f0 and w1 = f1 (s16, in 1/4096), h0 the
    seed, src the vacate level (layer old; layer new's src must be 0)."""
    return seg2(PIECES, n, kind=kind, src=vacate, xL=x, xR=xR or 0, y=y, dxL=dx, dxR=dxR or 0, dy=dy,
                lvl0=lvl[0], lvl1=lvl[1], opt=opt, h0=seed, w0=f[0], w1=f[1],
                aux0=grid[0] | grid[1] << 8, aux1=model | stagger << 8, **kw)
def sh(n, w=(0, 0), h=(0, 0), an=0, vacate=0, lvl=(15, 15), **kw):
    """SHRINK (§7.5): w0 w1 and h0 h1 (both 0 = that axis unscaled), anchor bits 0-3, src the vacate level."""
    return seg2(SHRINK, n, src=vacate, lvl0=lvl[0], lvl1=lvl[1], h0=h[0], h1=h[1], w0=w[0], w1=w[1], anchor=an, **kw)
def gly(n, kind, src, aux0=0, x=0, y=0, dx=0, dy=0, xR=None, dxR=None, lvl=(15, 15), seed=0, opt=0, **kw):
    """GLYPHS (§7.6): CELLS (src a cell table, aux0 the list's index) or RAIN (src a rain record, aux0 0); x, y, dx, dy
    are offsets; h0 the seed."""
    return seg2(GLYPHS, n, kind=kind, src=src, xL=x, xR=xR or 0, y=y, dxL=dx, dxR=dxR or 0, dy=dy,
                lvl0=lvl[0], lvl1=lvl[1], opt=opt, h0=seed, aux0=aux0, **kw)
def prog2(*tracks, fl=0, ntracks=None, length=None, tail=b"", bad=False, res=(0, 0), reg=True):
    """A version-2 program record (§7.2): version 2 · tracks · length u16 · flags (bit 0 OWN, bit 1 LAND) · 0 · 0 u16.
    `reg` False builds bytes the self-check does not expect a message to name (the budget's trial programs)."""
    body = b"".join(tracks) + tail
    rec = (bytes([2, len(tracks) if ntracks is None else ntracks]) + u16(8 + len(body) if length is None else length)
           + bytes([fl, res[0]]) + u16(res[1]) + body)
    if reg: (BAD_SHAPE if bad else WELL_FORMED).add(rec)
    return rec
def glyph(w, h, levels): return bytes([w, h]) + rle(levels)           # a v1 glyph record (§4)
def pad4(b): return b + bytes(-len(b) % 4)
def font224(entries): return b"".join(u16(o) for o in entries)         # §7.6, §4: 224 × off4, code c → entry c − 32
def alphabet(codes, n=None): return pad4(bytes([len(codes) if n is None else n]) + bytes(codes))
def cell(bx, by, bw, bh, font, old, new, fill=0xFF, opt=15):
    """One cell (§7.6, 12 B): bx by s16 · bw bh u8 · font off4 u16 · old new fill opt u8."""
    return s16(bx) + s16(by) + bytes([bw, bh]) + u16(font) + bytes([old, new, fill, opt])
def cell_list(alpha, noise, cells, count=None, res=0):
    """A cell list (§7.6): count u16 · alphabet off4 u16 · noise u8 · 0 · 0 · 0, then the cells."""
    return u16(len(cells) if count is None else count) + u16(alpha) + bytes([noise, res, 0, 0]) + b"".join(cells)
def cell_table(lists, n=None, res=0):
    """A cell table (§7.6): n u16 · 0 u16 · off4[n] u16, padded to 4 B."""
    return pad4(u16(len(lists) if n is None else n) + u16(res) + b"".join(u16(o) for o in lists))
def rain_rec(cols, trail, cw, ch, font, alpha, head=15, tail=3, reveal=0, fill=0, spread=0, flicker=1, res=0):
    """A rain record (§7.6, 16 B): cols trail cw ch u8 · font alphabet off4 u16 · headLvl tailLvl reveal fillLvl spread
    flicker u8 · 0 u16."""
    return (bytes([cols, trail, cw, ch]) + u16(font) + u16(alpha) + bytes([head, tail, reveal, fill, spread, flicker])
            + u16(res))
def play_after(slot, period, prog4, tag, after, P=()):                 # §7.7 mode 26 sub 8: 15 B after [26][8]
    return bytes([26, 8, slot, period]) + u16(prog4) + u16(tag) + bytes([after]) + _p4(P)
def bind_ring(lst, items, count, shift, pitch, mark, fl=1):            # §7.8 mode 27 sub 4: LIST's 10 B, then flags u8
    return bytes([27, 4, lst]) + u16(items) + u16(count) + s16(shift) + s16(pitch) + bytes([mark, fl])

# ---- the program reader and §7.12's budget calculator (spec §3 DV: "a budget calculator for v5-budget"; DM's
# `Motion.kt` calculator is its twin). It reads the bytes a vector writes, so the self-check also decodes every
# program a PLAY, PLAY-AFTER, SET or COMMIT names through it.
SEG_NAMES = ("verb", "steps", "ease", "kind", "src", "xL", "xR", "y", "dxL", "dxR", "dy", "lvl0", "lvl1", "opt", "res0",
             "table", "h0", "h1", "anchor", "res1", "pref0", "pref1")
SEG_FMT = struct.Struct("<4BH6h4B3H4B")            # §5.4's 32-byte segment
PREF_FIELDS = {0: ("src",), 1: ("xL",), 2: ("xR",), 3: ("xL", "xR"), 4: ("y",), 5: ("dxL",), 6: ("dxR",), 7: ("dxL", "dxR"),
               8: ("dy",), 9: ("lvl0",), 10: ("lvl1",), 11: ("h0",), 12: ("h1",), 13: ("steps",), 14: ("w0",), 15: ("w1",),
               16: ("aux0",), 17: ("aux1",)}
def rd16(b, p): return b[p] | b[p + 1] << 8
def parse_prog(buf, off):
    """Walk a program record at byte `off` (§5.4, §7.2) → (version, flags, length, tracks); a track is (backdrop,
    (rectL, rectR), segments) with a rect (l, t, w, h) and a segment a dict of its fields. ValueError when it does not
    end at its length."""
    if off + 4 > len(buf): raise ValueError("header past the cache")
    ver, nt, ln = buf[off], buf[off + 1], rd16(buf, off + 2)
    hdr, ss = (8, 40) if ver == 2 else (4, 32)
    fl = buf[off + 4] if ver == 2 and off + 5 <= len(buf) else 0
    p, tracks = off + hdr, []
    for _ in range(nt):
        if p + 4 > len(buf): raise ValueError("track past the cache")
        ns, bd, pl = buf[p], buf[p + 1], buf[p + 2]
        if p + 4 + (16 if pl else 8) > len(buf): raise ValueError("track past the cache")
        rL = struct.unpack_from("<4H", buf, p + 4)
        rR = struct.unpack_from("<4H", buf, p + 12) if pl else rL
        p += 4 + (16 if pl else 8)
        segs = []
        for _ in range(ns):
            if p + ss > len(buf): raise ValueError("segment past the cache")
            d = dict(zip(SEG_NAMES, SEG_FMT.unpack_from(buf, p)))
            if ver == 2: d.update(zip(("w0", "w1", "aux0", "aux1"), struct.unpack_from("<4H", buf, p + 32)))
            segs.append(d); p += ss
        tracks.append((bd, (rL, rR), segs))
    if p - off != ln: raise ValueError(f"walked {p - off} B, the header says {ln}")
    return ver, fl, ln, tracks
def subst(sg, P):
    """A segment's fields after §5.4's substitution (u8 fields take the low byte, u16 fields the whole value)."""
    d = dict(sg)
    for pr in (sg["pref0"], sg["pref1"]):
        if pr & 0x80:
            val = tuple(P)[pr & 3] if (pr & 3) < len(P) else 0
            for name in PREF_FIELDS.get((pr >> 2) & 0x1F, ()):
                d[name] = val & 0xFF if name in ("lvl0", "lvl1", "steps") else val & 0xFFFF if name in (
                    "src", "h0", "h1", "w0", "w1", "aux0", "aux1") else val
    return d
def rle_walk(buf, p, npix):
    """The tokens of an RLE stream at byte p (the v1 token format) up to npix pixels → [(first pixel, count, bytes)]."""
    out, px = [], 0
    while px < npix:
        b0 = buf[p]
        if b0 >> 4: n, nb = b0 >> 4, 1
        elif buf[p + 1]: n, nb = buf[p + 1], 2
        else: n, nb = rd16(buf, p + 2), 4
        if n == 0: raise ValueError("a token of 0 pixels")
        out.append((px, n, nb)); px += n; p += nb
    return out
def rec_L(buf, off4, a0=0, a1=0, memo=None):
    """A v2 record's width, height and L (§7.12): the bytes of the tokens that hold a pixel of the source's rows — the
    window's rows a0 .. a0 + a1 − 1, or every row when a1 = 0."""
    memo = {} if memo is None else memo
    if ("L", off4, a0, a1) not in memo:
        off = off4 * 4
        w, h = rd16(buf, off), rd16(buf, off + 2)
        lo, hi = (a0, a0 + a1) if a1 else (0, h)
        memo["L", off4, a0, a1] = w, h, sum(nb for px, n, nb in rle_walk(buf, off + 4, w * h)
                                            if px // w < hi and (px + n - 1) // w >= lo)
    return memo["L", off4, a0, a1]
def glyph_at(buf, font4, code, memo):
    """(w·h, the RLE's bytes) of the v1 glyph record a code names in a font (§7.6: entry code − 32)."""
    g = rd16(buf, font4 * 4 + 2 * (code - 32)) * 4
    if ("g", g) not in memo:
        w, h = buf[g], buf[g + 1]
        memo["g", g] = w * h, sum(nb for _, _, nb in rle_walk(buf, g + 2, w * h))
    return memo["g", g]
def cells_term(buf, s, Wa, Ha, memo):
    """§7.12 GLYPHS CELLS: Σ over the list's cells of min(bw, Wa)·min(bh, Ha) + 16·Lg_c + 10·g_c + 64, Lg_c the longest
    RLE and g_c the largest w·h (each its own maximum) among the glyphs the cell's old, new and alphabet codes name in
    its font — codes 0 and 32 name none; a cell that names none counts its box and 64."""
    lst = rd16(buf, s["src"] * 4 + 4 + 2 * s["aux0"]) * 4
    alpha = rd16(buf, lst + 2) * 4
    codes = list(buf[alpha + 1:alpha + 1 + buf[alpha]])
    t = 0
    for i in range(rd16(buf, lst)):
        c = lst + 8 + 12 * i
        bw, bh, font = buf[c + 4], buf[c + 5], rd16(buf, c + 6)
        gs = [glyph_at(buf, font, x, memo) for x in [buf[c + 8], buf[c + 9]] + codes if x not in (0, 32)]
        t += min(bw, Wa) * min(bh, Ha) + 64 + (16 * max(g[1] for g in gs) + 10 * max(g[0] for g in gs) if gs else 0)
    return t
def rain_term(buf, s, A, count32, memo):
    """§7.12 GLYPHS RAIN (as ruled, C3-decisions Y, C-5): A + cols·trail·(cw·ch + 64 + G), with G = 16·Lg + 10·cw·ch when
    the alphabet holds a code other than 32, else 0, and Lg the longest RLE length among the glyph records the alphabet's
    codes other than 32 name in its font (§7.6: code 32 names no glyph). `count32` builds the reading the contract did
    not take — code 32 naming its glyph like any code — which v5-budget's second rC pair still carries."""
    r = s["src"] * 4
    cols, trail, cw, ch, font, alpha = buf[r], buf[r + 1], buf[r + 2], buf[r + 3], rd16(buf, r + 4), rd16(buf, r + 6) * 4
    codes = [x for x in buf[alpha + 1:alpha + 1 + buf[alpha]] if count32 or x != 32]
    G = 16 * max(glyph_at(buf, font, x, memo)[1] for x in codes) + 10 * cw * ch if codes else 0
    return A + cols * trail * (cw * ch + 64 + G)
def cost(buf, off4, P=(), count32=False, memo=None):
    """§7.12's C of the program at `off4` under parameters P, unsigned and unbounded (Python ints): per track 1,024 + the
    backdrop's term + the largest of its segments' terms, from both lenses' rects (A the larger area, Wa and Ha the larger
    width and height); a LAND program adds its owned rect's area, the larger lens's. `memo` caches record and glyph
    walks for one cache whose records do not move."""
    memo = {} if memo is None else memo
    ver, fl, _, tracks = parse_prog(buf, off4 * 4)
    total = 0
    for bd, (rL, rR), segs in tracks:
        A, Wa, Ha = max(rL[2] * rL[3], rR[2] * rR[3]), max(rL[2], rR[2]), max(rL[3], rR[3])
        bdt = A if bd <= 15 or bd == BD_NEW else 10 * A if bd == BD_SELF else 0
        terms = [0]
        for sg in segs:
            s = subst(sg, P)
            vb, k = s["verb"], s["kind"]
            wide = ver == 2 and bool(s["w0"] or s["w1"])           # "when its width scales"
            if vb == FILL: t = A
            elif vb in (DRAW, REVEAL) and k == K_RECORD:
                a0, a1 = (s["aux0"], s["aux1"]) if ver == 2 and vb == DRAW else (0, 0)
                rw, _, L = rec_L(buf, s["src"], a0, a1, memo)
                t = 26 * L + (10 * A + rw * Ha if vb == DRAW and wide else 4 * A)
            elif vb in (DRAW, REVEAL) and k == K_SELF: t = 10 * A
            elif vb == REVEAL: t = A                               # a level or `new`
            elif vb == DRAW: t = 10 * A if wide else 3 * A          # `new`
            elif vb == PIECES: t = 3 * A
            elif vb == SHRINK: t = 13 * A if wide else 3 * A
            elif vb == GLYPHS and k == CELLS: t = cells_term(buf, s, Wa, Ha, memo)
            elif vb == GLYPHS: t = rain_term(buf, s, A, count32, memo)
            else: t = 0
            terms.append(t)
        total += 1024 + bdt + max(terms)
    if fl & LAND:
        def box(i):
            rs = [r[i] for _, r, _ in tracks]
            return ((max(q[0] + q[2] for q in rs) - min(q[0] for q in rs))
                    * (max(q[1] + q[3] for q in rs) - min(q[1] for q in rs)))
        total += max(box(0), box(1))
    return total
def pcost(c, rec, P=(), count32=False, memo=None):
    """`cost` of a record not yet in the cache `c` (Cache2): laid after the cache's bytes, 4-byte aligned."""
    buf = bytearray(c.buf) + bytes(-len(c.buf) % 4)
    off4 = len(buf) // 4
    return cost(buf + rec, off4, P, count32, memo)
def walk_glyphs(buf, off):
    """The self-check's reader for what a GLYPHS segment names (§7.6): for each GLYPHS segment of a program whose src
    and aux0 no pref replaces, the cell table, the list aux0 picks, its alphabet and cells, or the rain record and its
    alphabet, every field in its range, and every glyph a code names decoding to its w·h. Returns the structures read."""
    ver, _, _, tracks = parse_prog(buf, off)
    n = 0
    def glyphs(font4, codes):
        for x in codes:
            if x in (0, 32): continue
            g = rd16(buf, font4 * 4 + 2 * (x - 32)) * 4
            assert g + 2 <= len(buf) and buf[g] and buf[g + 1], f"glyph {x} of font {font4}"
            rle_walk(buf, g + 2, buf[g] * buf[g + 1])
    def alpha_codes(a4):
        a = a4 * 4
        assert 1 <= buf[a] <= 224 and all(32 <= x <= 255 for x in buf[a + 1:a + 1 + buf[a]]), f"alphabet {a4}"
        return list(buf[a + 1:a + 1 + buf[a]])
    for _, _, segs in tracks:
        for s in segs:
            if ver != 2 or s["verb"] != GLYPHS or any(pr & 0x80 and (pr >> 2) & 0x1F in (0, 16) for pr in (s["pref0"], s["pref1"])):
                continue
            if s["kind"] == CELLS:
                t = s["src"] * 4
                assert 1 <= rd16(buf, t) <= 1024 and rd16(buf, t + 2) == 0 and s["aux0"] < rd16(buf, t), f"cell table {s['src']}"
                lst = rd16(buf, t + 4 + 2 * s["aux0"]) * 4
                cnt = rd16(buf, lst)
                assert 1 <= cnt <= 512 and buf[lst + 5:lst + 8] == bytes(3), f"cell list at {lst}"
                codes = alpha_codes(rd16(buf, lst + 2))
                for i in range(cnt):
                    q = lst + 8 + 12 * i
                    old, new, fil, opt = buf[q + 8:q + 12]
                    assert all(x == 0 or 32 <= x for x in (old, new)) and (fil <= 15 or fil == 0xFF) and not opt & 0xD0, f"cell {i}"
                    glyphs(rd16(buf, q + 6), [old, new] + codes)
            else:
                r = s["src"] * 4
                cols, trail, cw, ch = buf[r:r + 4]
                head, tail, rev, fil, _, flick = buf[r + 8:r + 14]
                assert 1 <= cols <= 128 and 1 <= trail <= 32 and cw and ch and max(head, tail, fil) <= 15 and rev <= 2 \
                    and flick and rd16(buf, r + 14) == 0, f"rain record {s['src']}"
                glyphs(rd16(buf, r + 4), alpha_codes(rd16(buf, r + 6)))
            n += 1
    return n

def vectors_v5(kf):
    """The C3 set (`FIRMWARE.md` §7.13, spec §4): every vector carries "panel" and features [0xdff]; every input, engine
    message and wake follows a `tick` op in its step. No v5 vector has a self-test form. Each step's comment names the
    §7 sentence it exercises."""
    v = []
    P = 34                                                        # §5.3's starting period
    def writes(c): return [msg(m) for m in c.messages()]
    def vec(name, steps, **extra): v.append({"name": name, "steps": steps, **extra})
    def arm(c, t=1000, frame=True, fl=FULL5, size=None):
        return step(tk(t), {"lease": "acquire"}, *([cachesize(size)] if size else []), flags(fl), page(True),
                    *([msg(kf)] if frame else []), *writes(c))
    def font(c, seed, sizes=((6, 10), (7, 12), (5, 9), (8, 11), (4, 8), (9, 13))):
        """Six glyph records and a 224-entry font naming them in turn (code c → glyph (c − 32) mod 6)."""
        gs = [c.add(glyph(w, h, pattern(w, h, seed + i))) for i, (w, h) in enumerate(sizes)]
        return c.add(font224([gs[k % len(gs)] for k in range(224)])), gs
    def play_to_end(slot, prog4, tag, t, T, Pp=P, every=True, Pv=()):
        """A PLAY at t, then wakes at each step (or only at step T) to its last step."""
        out = [step(tk(t), msg(play(slot, Pp, prog4, tag, P=Pv)))]
        out += [step(tk(t + k * Pp), wake()) for k in (range(1, T) if every else [T - 1]) if k]
        return out
    def refusal_run(st, refusals, ok, t, tag0, c):
        """v3-order's form: each refused message, then an accepted PLAY of `ok` in slot 0 whose present draws step 1
        (a refusal exit that kept the engine word would fold it), then a wake at its last step (T 4)."""
        for i, (pre, m, post) in enumerate(refusals):
            if post == "rearm": post = [{"lease": "acquire"}, flags(FULL5), msg(kf), *writes(c)]
            st.append(step(*pre, tk(t), msg(m), *post, tk(t + 5), msg(play(0, P, ok, tag0 + i))))
            st.append(step(tk(t + 5 + 3 * P), wake()))
            t += 200
        return t

    # ---------------------------------------------------------------------------------------------------------------
    # v5-order — §7.2 "The check order of a PLAY, PLAY-AFTER and RETARGET on a bit-11 build (§5.5's, with C3's checks in
    # place): no shadow (4) → length (1; PLAY-AFTER 17 B with the two header bytes) → the lease (3) → the engine's busy
    # word (14) → DRAW2 (9) → ENGINE (15) → PLAY-AFTER: EFFECTS (15) → slot and period (12; PLAY-AFTER: after 0..3 and
    # not slot, 12) → RETARGET: an empty slot (16), then the shape rule (12) → the walk (5) ... → areas (2) → the
    # none-backdrop rule (2) → values (12): PLAY-AFTER: the program carries OWN; the in-place track's shape, place, still
    # offsets and later tracks; then every segment field's range ... → the step budget (12) → the capture range (5) →
    # RETARGET: the new first range (5)"; "A SET checks only what the program record's own bytes decide ... A fire and a
    # COMMIT apply every rule of this section at their own walk (5) and values (12) positions"; §7.9 EFFECTS. Each refusal
    # carries a later check's fault where one fits, so only the stated order records its reason. 4, 14 and 11 reach no
    # vector (C1's A.6: nothing holds the word between two host messages; the handles are static): host pins.
    c = Cache2(); fnt, _ = font(c, 501)
    pic = c.add(image2(40, 30, pattern(40, 30, 502)))
    ovt = c.add(easing([1500, 3500, 4600, 4096]))                     # E above 4096: a level past 15
    fall = c.add(easing([1500, 1200, 3000, 4096]))                    # E falls (1500 → 1200): an in-place ease's 5
    rise = c.add(easing([300, 1200, 3000, 4096]))                     # never falls: taken in place
    alpha = c.add(alphabet(range(65, 75)))
    ctab = c.add(cell_table([c.add(cell_list(alpha, 1, [cell(110, 50, 8, 12, fnt, 65, 66, 3), cell(120, 50, 8, 12, fnt, 67, 68, 3)]))]))
    rain1 = c.add(rain_rec(4, 3, 8, 12, fnt, alpha, reveal=1))
    rain2 = c.add(rain_rec(4, 3, 8, 12, fnt, alpha, reveal=2))
    ok = c.add(prog2(track(rect(0, 400, 300, 40), 2, d2(4, pic, 10, 405, dx=200))))   # T 4, version 2: step 1 differs from END
    offp, R1, OA = rect(600, 40, 100, 40), rect(0, 40, 200, 40), rect(100, 40, 200, 80)
    def bp(*tr, **kw): return c.add(prog2(*tr, bad=True, **kw))
    def gp(*tr, **kw): return c.add(prog2(*tr, **kw))
    inpl = lambda area=OA, bd=BD_NONE, **kw: track(area, bd, pc(4, K_OLD, dx=20, vacate=3, **kw))   # moves pixels
    own_ok = gp(inpl(), fl=OWN)
    NE = flags(DRAW2 | ENGINE | BIND | ZWRITE)                        # EFFECTS clear
    E5 = [flags(FULL5)]
    refusals = [
        # PLAY-AFTER: length, lease, DRAW2, ENGINE, EFFECTS, slot / period / after, then the walk, areas, values
        ([], bad(play_after(4, P, 0xFFFF, 0, 4)[:-1]), []),                          # 16 B: 1 before slot, after, the walk
        ([], bad(play_after(0, P, own_ok, 0, 1) + b"\0"), []),                        # 18 B: 1
        ([], bad(bytes([26, 8])), []),                                                # 2 B: 1
        ([{"lease": "release"}], play_after(4, 15, 0xFFFF, 0, 4), "rearm"),          # the lease (3) first
        ([flags(ENGINE | BIND | EFFECTS)], play_after(4, 15, 0xFFFF, 0, 4), E5),      # DRAW2 (9)
        ([flags(DRAW2 | BIND | EFFECTS)], play_after(4, 15, 0xFFFF, 0, 4), E5),       # ENGINE (15)
        ([NE], play_after(4, 15, 0xFFFF, 0, 4), E5),                                  # EFFECTS (15) before slot, period, after
        ([], play_after(4, P, 0xFFFF, 0, 1), []),                                    # slot 4: 12 before the walk's 5
        ([], play_after(0, 15, 0xFFFF, 0, 1), []),                                   # period 15: 12
        ([], play_after(0, 129, own_ok, 0, 1), []),                                  # period 129: 12
        ([], play_after(0, P, 0xFFFF, 0, 4), []),                                    # after 4: 12
        ([], play_after(1, P, 0xFFFF, 0, 1), []),                                    # after = slot: 12
        ([], play_after(0, P, 0xFFFF, 0, 1), []),                                    # the program past the cache: 5
        ([], play_after(0, P, bp(inpl(offp, 4), fl=OWN), 0, 1), []),                 # in place with a level backdrop (5) off the panel (2): 5
        ([], play_after(0, P, gp(track(offp, 2, d2(4, pic, 610, 45))), 0, 1), []),   # not OWN (12) and off the panel (2): 2
        ([], play_after(0, P, gp(track(R1, 2, d2(4, pic, 10, 45))), 0, 1), []),      # not OWN: 12 ("the program carries OWN")
        ([], play_after(0, P, gp(track(R1, 2, f2(4, 16)), fl=OWN), 0, 1), []),       # OWN, a FILL level 16: 12
        ([msg(bytes([16, 0]))], bytes([16, 1]) + play_after(0, P, own_ok, 0, 1), [msg(bytes([16, 2]))]),   # in a self-test step: 8 (§7.7)
        # PLAY of a version-2 record: the walk's 5s
        ([NE], play(0, P, gp(track(offp, 2, d2(4, pic, 610, 45))), 0), E5),         # version 2 with EFFECTS clear: 5 before areas (2)
        ([], play(0, P, bp(track(R1, 2, d2(4, pic, 10, 45)), fl=0x04), 0), []),     # header flag bit 2: 5
        ([], play(0, P, bp(track(R1, 2, d2(4, pic, 10, 45)), fl=LAND), 0), []),     # LAND without OWN: 5
        ([], play(0, P, bp(track(R1, 2, d2(4, pic, 10, 45)), res=(1, 0)), 0), []),  # the header's reserved byte: 5
        ([], play(0, P, bp(track(R1, 2, d2(4, pic, 10, 45)), res=(0, 1)), 0), []),  # its reserved u16: 5
        ([], play(0, P, bp(track(R1, 2, seg2(7, 4))), 0), []),                      # verb 7: 5
        ([], play(0, P, bp(track(R1, 2, d2(4, pic, 10, 45, kind=1))), 0), []),      # DRAW kind 1: 5
        ([], play(0, P, bp(track(R1, 2, d2(4, pic, 10, 45, kind=3))), 0), []),      # DRAW kind 3: 5
        ([], play(0, P, bp(track(R1, 2, r2(4, TOP, 1, 10, 45, src=pic))), 0), []),  # REVEAL kind 1: 5
        ([], play(0, P, bp(track(OA, 2, pc(4, 1)), fl=OWN), 0), []),                # PIECES kind 1: 5
        ([], play(0, P, bp(track(OA, 2, pc(4, 2)), fl=OWN), 0), []),                # PIECES kind 2: 5
        ([], play(0, P, bp(track(OA, BD_NONE, seg2(SHRINK, 4, kind=1)), fl=OWN), 0), []),    # SHRINK kind 1: 5
        ([], play(0, P, bp(track(OA, 2, gly(4, 2, ctab)), fl=OWN), 0), []),         # GLYPHS kind 2: 5
        ([], play(0, P, bp(track(R1, 0x22, d2(4, pic, 10, 45))), 0), []),           # backdrop 0x22: 5
        ([], play(0, P, bp(track(R1, 2, d2(4, pic, 10, 45, pref0=pref(0, 18)))), 0), []),   # pref 18 in version 2: 5
        ([], play(0, P, c.add(prog(track(R1, 2, drawrec(4, pic, 10, 45, pref0=pref(0, F_W0))), bad=True)), 0), []),   # pref 14 in version 1: 5
        # unused fields (verbs 0–3: w0, w1, aux0, aux1 only; verbs 4–6: every field not listed), anchors, opt bits
        ([], play(0, P, bp(track(R1, 2, f2(4, 3, w0=1))), 0), []),                  # FILL w0: 5
        ([], play(0, P, bp(track(R1, 2, w2(4, aux1=1))), 0), []),                   # WAIT aux1: 5
        ([], play(0, P, bp(track(R1, 2, r2(4, TOP, K_LEVEL, 10, 45, src=5, w=20, h=10, aux0=1))), 0), []),   # REVEAL aux0: 5
        ([], play(0, P, bp(track(OA, BD_NONE, sh(4, h=(80, 40), xL=1)), fl=OWN), 0), []),          # SHRINK xL: 5
        ([], play(0, P, bp(track(OA, BD_NONE, sh(4, h=(80, 40), aux0=1)), fl=OWN), 0), []),        # SHRINK aux0: 5
        ([], play(0, P, bp(track(OA, BD_NONE, sh(4, h=(80, 40), dy=1)), fl=OWN), 0), []),          # SHRINK dy: 5
        ([], play(0, P, bp(track(OA, BD_NONE, pc(4, K_OLD, dx=20, h1=1)), fl=OWN), 0), []),        # PIECES h1: 5
        ([], play(0, P, bp(track(OA, BD_NONE, pc(4, K_OLD, dx=20, anchor=1)), fl=OWN), 0), []),    # PIECES anchor: 5
        ([], play(0, P, bp(track(R1, 2, gly(4, CELLS, ctab, w0=1))), 0), []),       # GLYPHS w0: 5
        ([], play(0, P, bp(track(R1, 2, gly(4, CELLS, ctab, aux1=1))), 0), []),     # GLYPHS aux1: 5
        ([], play(0, P, bp(track(R1, 2, gly(4, CELLS, ctab, h1=1))), 0), []),       # GLYPHS h1: 5
        ([], play(0, P, bp(track(R1, 2, gly(4, CELLS, ctab, anchor=1))), 0), []),   # GLYPHS anchor: 5
        ([], play(0, P, bp(track(R1, 2, d2(4, pic, 10, 45, anchor=anc(0, 3)))), 0), []),   # DRAW horizontal anchor 3: 5
        ([], play(0, P, bp(track(R1, 2, d2(4, pic, 10, 45, anchor=0x10))), 0), []),        # anchor bit 4: 5
        ([], play(0, P, bp(track(OA, BD_NONE, sh(4, h=(80, 40), an=anc(3, 0))), fl=OWN), 0), []),  # SHRINK vertical anchor 3: 5
        ([], play(0, P, bp(track(OA, BD_NONE, sh(4, h=(80, 40), an=0x40)), fl=OWN), 0), []),       # SHRINK anchor bit 6: 5
        ([], play(0, P, bp(track(OA, 2, pc(4, K_NEW, opt=0x01)), fl=OWN), 0), []),                 # PIECES layer new opt bit 0: 5
        ([], play(0, P, bp(track(OA, BD_NONE, pc(4, K_OLD, dx=20, opt=0x10)), fl=OWN), 0), []),    # layer old opt 4: 5
        ([], play(0, P, bp(track(OA, BD_NONE, pc(4, K_OLD, dx=20, opt=0x40)), fl=OWN), 0), []),    # layer old opt 6: 5
        ([], play(0, P, bp(track(OA, BD_NONE, sh(4, h=(80, 40), opt=0x80)), fl=OWN), 0), []),      # SHRINK opt 7: 5
        ([], play(0, P, bp(track(R1, 2, gly(4, CELLS, ctab, opt=0x10))), 0), []),                  # GLYPHS opt 4: 5
        # the context rules (5)
        ([], play(0, P, bp(track(R1, BD_SELF, w2(4)), fl=OWN), 0), []),             # `self` backdrop in OWN: 5
        ([], play(0, P, bp(track(R1, 2, d2(4, 0, 0, 40, kind=K_SELF)), fl=OWN), 0), []),   # DRAW `self` in OWN: 5
        ([], play(0, P, bp(track(R1, 2, dnew(4, 0, 40))), 0), []),                  # DRAW `new` outside OWN: 5
        ([], play(0, P, bp(track(R1, BD_NEW, w2(4))), 0), []),                      # backdrop `new` outside OWN: 5
        ([], play(0, P, bp(track(R1, BD_NONE, r2(4, TOP, K_NEW, 0, 40))), 0), []),  # REVEAL `new` outside OWN: 5
        ([], play(0, P, bp(track(OA, 2, pc(4, K_NEW))), 0), []),                    # PIECES layer new outside OWN: 5
        ([], play(0, P, bp(inpl()), 0), []),                                        # PIECES layer old outside OWN: 5
        ([], play(0, P, bp(track(OA, BD_NONE, sh(4, h=(80, 40)))), 0), []),         # SHRINK outside OWN: 5
        ([], play(0, P, bp(track(OA, BD_NONE, gly(4, RAIN, rain1))), 0), []),       # rain reveal 1 outside OWN: 5
        ([], play(0, P, bp(track(OA, BD_NONE, gly(4, RAIN, rain2))), 0), []),       # rain reveal 2 (in place) outside OWN: 5
        ([], play(0, P, bp(inpl(bd=4), fl=OWN), 0), []),                            # an in-place backdrop not none: 5
        ([], play(0, P, bp(inpl(ease=TABLE, table=fall), fl=OWN), 0), []),          # an in-place table that falls: 5
        ([], play(0, P, bp(inpl(offp, bd=4), fl=OWN), 0), []),                      # the walk's 5 before areas (2)
        # the none-backdrop rule (2) and values (12)
        ([], play(0, P, gp(track(OA, BD_NONE, dnew(4, 100, 40)), fl=OWN), 0), []),  # DRAW `new`, none, first: 2
        ([], play(0, P, gp(track(OA, BD_NONE, r2(4, TOP, K_NEW, 100, 40, ease=TABLE, table=fall)), fl=OWN), 0), []),   # REVEAL in OWN, an ease that falls: 2
        ([], play(0, P, gp(track(R1, BD_NONE, r2(4, TOP, K_LEVEL, 10, 45, src=5, w=20, h=10))), 0), []),   # REVEAL, none, not OWN: 2
        ([], play(0, P, gp(inpl(), inpl(rect(320, 40, 200, 80)), fl=OWN), 0), []),  # two in-place tracks: 12
        ([], play(0, P, gp(track(rect(320, 40, 100, 40), 2, w2(4)), inpl(), fl=OWN), 0), []),   # an in-place track not first: 12
        ([], play(0, P, gp(track(OA, BD_NONE, pc(2, K_OLD, dx=20), pc(2, K_OLD, dx=10)), fl=OWN), 0), []),   # two non-WAIT segments in place: 12
        ([], play(0, P, gp(track(OA, BD_NONE, gly(4, RAIN, rain2, dx=10)), fl=OWN), 0), []),   # rain in place with dx: 12
        ([], play(0, P, gp(inpl(), track(rect(250, 60, 100, 20), 5, f2(4, 7)), fl=OWN), 0), []),   # a later track meeting a moving in-place track: 12
        ([], play(0, P, gp(track(R1, 2, f2(4, 16))), 0), []),                       # a FILL level 16: 12
        ([], play(0, P, gp(track(R1, 2, d2(4, pic, 10, 45, lvl=(0, 15), ease=TABLE, table=ovt))), 0), []),   # DRAW's eased level past 15: 12
        ([], play(0, P, gp(track(OA, 2, pc(4, K_NEW, lvl=(0, 15), ease=TABLE, table=ovt)), fl=OWN), 0), []),   # PIECES' eased level past 15: 12
        ([], play(0, P, gp(track(R1, 2, gly(4, CELLS, ctab, lvl=(0, 15), ease=TABLE, table=ovt))), 0), []),   # GLYPHS' eased level past 15: 12
        ([], play(0, P, gp(track(R1, 2, f2(4, 3, h0=1))), 0), []),                  # C1's "h0 and h1 are 0" binds FILL: 12
        ([], play(0, P, gp(track(R1, 2, r2(4, TOP, K_LEVEL, 10, 45, src=5, w=20, h=10, h1=1))), 0), []),   # and REVEAL: 12
        ([], play(0, P, gp(track(R1, 2, w2(4, h0=1))), 0), []),                     # and WAIT: 12
    ]
    sc16 = gp(track(rect(0, 300, 41, 20), BD_SELF, w2(4)), track(R1, 2, f2(4, 16)))   # a `self` capture and a FILL level 16
    sc = gp(track(rect(0, 300, 41, 20), BD_SELF, w2(4)), track(R1, 2, f2(4, 3)))
    refusals += [
        ([], play(0, P, sc16, 0x5090, 0xFFFF), []),                                   # values (12) before the capture range (5): 12
        ([], play(0, P, sc, 0x5091, 0xFFFF), []),                                     # the capture range past the cache: 5
    ]
    taken = [gp(track(OA, 2, pc(4, K_NEW, seed=5000)), fl=OWN),               # "a seed of 5,000" (PIECES' h0): taken
             gp(track(R1, 2, gly(4, CELLS, ctab, seed=5000))),                      # GLYPHS' h0 5,000: taken
             gp(track(R1, 2, f2(4, 3, src=9))),                                     # FILL's src, a field verbs 0–3 ignore: taken
             gp(track(OA, BD_NONE, w2(2), pc(4, K_OLD, dx=20, vacate=3)), fl=OWN),  # WAITs and one non-WAIT in place: taken
             gp(track(OA, BD_NONE, pc(4, K_OLD, dx=20, ease=TABLE, table=rise)), fl=OWN),   # an in-place table that never falls: taken
             gp(inpl(), track(rect(320, 60, 100, 20), 5, f2(4, 7)), fl=OWN),        # a later track apart from the moving one: taken
             gp(track(OA, BD_NONE, pc(4, K_OLD)), track(rect(250, 60, 40, 20), 5, f2(4, 7)), fl=OWN),   # over a still in-place track: taken
             gp(track(OA, BD_NONE, r2(4, TOP, K_NEW, 100, 40, ease=EASE_OUT)), fl=OWN)]   # REVEAL `new` in OWN, none, non-decreasing: taken
    # RETARGET: an empty slot (16), then the shape rule (12), the walk (5), values (12)
    wnd = gp(track(rect(0, 200, 120, 40), 2, d2(4, pic, 0, 200, aux1=10, pref0=pref(0, F_AUX0))))     # window first row = P0
    wid = gp(track(rect(0, 260, 120, 40), 2, d2(4, pic, 0, 260, pref0=pref(0, F_W0), pref1=pref(1, F_W1))))   # w0, w1 = P0, P1
    celp = gp(track(rect(320, 200, 120, 40), 2, gly(4, CELLS, ctab)))
    # SET, fire and COMMIT (bound programs: list 0, slot 1)
    A0b = rect(100, 300, 120, 40)
    sp_b = prog2(track(A0b, 2, d2(4, pic, 100, 0, pref0=pref(0, F_Y))))           # the fire's program, rewritten in place below
    sp = c.add(sp_b)
    sp_own_b = prog2(track(A0b, 2, d2(4, pic, 100, 0, pref0=pref(0, F_Y))), fl=OWN, reg=False)
    sp_inpl_b = prog2(track(A0b, BD_NONE, pc(4, K_OLD, dx=20, pref0=pref(0, F_Y))), reg=False)
    assert len(sp_b) == len(sp_own_b) == len(sp_inpl_b)
    s_own = gp(track(A0b, 2, d2(4, pic, 100, 0)), fl=OWN)
    s_new = gp(track(A0b, 2, dnew(4, 100, 300)))
    s_pcs = bp(track(A0b, BD_NONE, pc(4, K_OLD, dx=20)))
    s_shr = bp(track(A0b, BD_NONE, sh(4, h=(40, 20))))
    s_past = gp(track(A0b, 2, d2(4, PAST, 100, 0, pref0=pref(0, F_Y))))           # a record past the cache: the SET does not walk it
    s_l16 = gp(track(A0b, 2, f2(4, 16)))                                          # a level 16: values, not the SET's
    s_idx = bp(track(A0b, 2, gly(4, CELLS, ctab, aux0=5)))                        # a cell table's index past n: the walk's
    s_r1 = gp(track(A0b, BD_NONE, gly(4, RAIN, rain1)))                            # rain reveal 1 outside OWN: the rain record's walk
    s_r2 = gp(track(A0b, BD_NONE, gly(4, RAIN, rain2)))
    v2e = gp(track(rect(0, 200, 640, 60), 3, d2(6, pic, 0, 210, dx=500)))         # T 6
    v1e = c.add(prog(track(rect(0, 300, 640, 40), 4, drawrec(6, pic, 0, 305, dx=500))))
    pw1 = c.add(prog2(track(A0b, 2, f2(4, 3, pref0=pref(0, F_W1)))))             # §7.2 (b24a110): a pref 15 into a FILL's w1
    ph1 = c.add(prog2(track(OA, 2, pc(4, K_NEW, pref0=pref(0, F_H1))), fl=OWN))   # and a pref 12 into PIECES' h1 (unused there)
    st = [arm(c)]
    t = refusal_run(st, refusals, ok, 2000, 0x5000, c)
    st += [step(tk(t + 10 * i), msg(play(0, P, p_, 0x5100 + i))) for i, p_ in enumerate(taken)]   # each taken: its present draws step 1
    t += 200
    st += [step(tk(t), msg(retarget(3, (0,)))),                                     # an empty slot: 16
           step(tk(t + 10), msg(play(1, P, own_ok, 0x5110)), tk(t + 10), msg(retarget(1))),   # an OWN instance: the shape rule's 12
           step(tk(t + 20), msg(play(1, P, celp, 0x5111)), tk(t + 20), msg(retarget(1))),     # a GLYPHS segment: 12
           step(tk(t + 30), msg(play(1, P, wnd, 0x5112, P=(0,))), tk(t + 30), msg(retarget(1, (25,)))),   # window rows 25..34 of 30: the walk's 5
           step(tk(t + 40), msg(retarget(1, (20,)))),                               # rows 20..29: taken (§7.2 "checked again by its walk")
           step(tk(t + 50), msg(play(2, P, wid, 0x5113, P=(40, 40))), tk(t + 50), msg(retarget(2, (0, 50)))),   # w0 0, w1 50: values 12
           step(tk(t + 60), msg(close(0xFF)))]
    t += 200
    # §7.9 "A FLAGS_SET of it alone is taken (status 0) ... refused without the lease (status 3)"; "A disarm closes
    # nothing ...: open instances run on to their end ... and the next version-2 PLAY is refused 5"; it clears at
    # every release point
    st += [step(tk(t), flags(EFFECTS), msg(play(0, P, ok, 0x5120))),               # EFFECTS alone: status 0; the PLAY 9 (DRAW2 clear)
           step(tk(t + 10), flags(FULL5), msg(play(0, P, v2e, 0x5121))),            # armed: taken, step 1
           step(tk(t + 10 + P), wake()),                                            # step 2
           step(tk(t + 10 + P + 5), NE),                                            # EFFECTS cleared mid-motion: closes nothing
           step(tk(t + 10 + 2 * P), wake()),                                        # runs on: step 3
           step(tk(t + 10 + 5 * P), wake()),                                        # step 6 = T: outcome 0
           step(tk(t + 300), msg(play(1, P, ok, 0x5122))),                          # the next version-2 PLAY: 5
           step(tk(t + 310), msg(play_after(2, P, own_ok, 0x5123, 1))),             # a PLAY-AFTER: 15
           step(tk(t + 320), msg(play(1, P, v1e, 0x5124))),                         # a version-1 PLAY: taken
           step(tk(t + 330), {"lease": "release"}, flags(EFFECTS)),                 # no lease: status 3
           step(tk(t + 340), {"lease": "acquire"}, NE, msg(kf), *writes(c), tk(t + 340), msg(play(0, P, ok, 0x5125))),   # cleared at the release point: 5
           step(tk(t + 350), flags(FULL5), msg(play(0, P, ok, 0x5126)))]            # armed again: taken
    t += 600
    # the SET's narrower check, then the fire's and the COMMIT's full walk (§7.2; §6.2 step 10; §6.5)
    up = lambda p4, e=0: msg(bind_set(e, ANY, UP, 0, 1, 1, P, p4, [(K_PITCH, 300)]))
    st += [step(tk(t), msg(bind_list(0, NONE, 8, 0, 10, 1)), up(sp), msg(bind_set(1, ANY, DOWN, 0, -1, 1, P, sp, [(K_PITCH, 300)])))]
    for i, p_ in enumerate([s_own, s_new, s_pcs, s_shr]):                           # OWN, `new`, PIECES kind 0, SHRINK at a SET: 5
        st.append(step(tk(t + 10 + i), up(p_)))
    st += [step(tk(t + 20), NE, tk(t + 20), up(ok), flags(FULL5))]                            # a version-2 record with EFFECTS clear at a SET: 5
    t += 100
    for i, (p_, why) in enumerate([(s_past, "5"), (s_l16, "12"), (s_idx, "5"), (s_r1, "5"), (s_r2, "5")]):
        st.append(step(tk(t + 20 * i), up(p_), inp(RING, UP)))                      # the SET takes it; the fire refuses it (`why`)
    t += 200
    st += [step(tk(t), up(sp), msg(cache_write2((sp, sp_own_b))), inp(RING, UP)),   # OWN at a fire: 5 (the entry names rewritten bytes)
           step(tk(t + 10), msg(cache_write2((sp, sp_inpl_b))), inp(RING, UP)),      # an in-place track at a fire (not OWN): 5
           step(tk(t + 20), msg(cache_write2((sp, sp_b))), inp(RING, UP)),           # the program back: drew
           step(tk(t + 20), wake())]
    for i, p_ in enumerate([s_own, s_new, s_pcs, s_shr]):                           # OWN, `new`, PIECES kind 0, SHRINK at a COMMIT: 5
        st.append(step(tk(t + 30 + i), msg(commit(2, 0x7777, 0, 0, p_))))
    st.append(step(tk(t + 40), msg(commit(2, 0x7777, 0, 0, sp, (300,)))))            # the look-alike: taken
    # §7.2 (b24a110) "These rules are decided on the segment after substitution: a pref that writes a parameter into
    # such a field (pref 14–17 on any verb, any code on verbs 4–6) is 5 at a PLAY, PLAY-AFTER, RETARGET, fire or COMMIT
    # whose parameter there is not 0; a SET, which has no parameters, decides with every parameter 0"
    t += 200
    st += [step(tk(t), msg(bind_set(0, ANY, UP, 0, 1, 1, P, pw1, [(K_ARG, 5)])), inp(RING, UP)),   # taken at the SET; the fire (P0 5): 5
           step(tk(t + 10), msg(bind_set(0, ANY, UP, 0, 1, 1, P, pw1, [(K_ARG, 0)])), inp(RING, UP)),   # P0 0: drew
           step(tk(t + 10), wake()),
           step(tk(t + 20), msg(play(2, P, pw1, 0x5130, P=(5,)))),                 # a PLAY whose parameter is not 0: 5
           step(tk(t + 30), msg(play(2, P, pw1, 0x5131, P=(0,)))),                 # 0: taken
           step(tk(t + 40), msg(play(3, P, ph1, 0x5132, P=(3,)))),                 # PIECES' h1 by a pref, not 0: 5
           step(tk(t + 50), msg(play(3, P, ph1, 0x5133, P=(0,)))),                 # 0: taken
           step(tk(t + 60), msg(commit(2, 0x7777, 0, 0, pw1, (5,)))),              # at a COMMIT, not 0: 5
           step(tk(t + 70), msg(close(0xFF)))]
    vec("v5-order", st)

    # ---------------------------------------------------------------------------------------------------------------
    # v5-own — §7.3 "Owning. An instance opened by a PLAY or PLAY-AFTER of an OWN program carries an owns mark ... Its
    # owned rect on each lens is the bounding box of its tracks' rects on that lens. The mark is cleared by LAND, by every
    # close or emptying of the instance (CLOSE, a close pass, a PLAY into its slot, a live keyframe, a cache write that
    # meets its read ranges ...), on the stock path ..., at mode 11 and at every release point"; "Meeting ... two owning
    # instances meet when their owned rects intersect"; "The held present ... the copy hook's direct path copies the
    # shadow into the framebuffer except inside the job's rects"; "Steps. An owning instance draws a step only when k > d
    # ... A present on the direct path that draws no step may carry its mode-24 hint"; "No END ... field 8, RIGHT's CRC-32
    # of the shadow over the instance's areas"; "Step T ... With LAND the hook copies the shadow into the owned rect in
    # place of painting step T's tracks ... Without LAND the instance is done and keeps owning"; "What each close does ...
    # except inside the rects of instances that own at that present ... A close by a cache write presents nothing"; "The
    # fire. A fire whose close set holds an owning instance is refused 19, nothing closed ... a report-only fire closes
    # nothing and is not tested ... A COMMIT's close pass closes an owning instance as any other"; §7.2 RETARGET's 12.
    c = Cache2()
    pic = c.add(image2(40, 30, pattern(40, 30, 511)))
    OA, OB = rect(100, 80, 200, 120), rect(360, 80, 160, 100)
    out_ = c.add(prog2(track(OA, BD_NONE, pc(5, K_OLD, dx=60, dy=-10, lvl=(15, 6), vacate=2)), fl=OWN))   # an out half: uniform, dimmed
    inl = c.add(prog2(track(OA, BD_NONE, r2(4, LEFT, K_NEW, 100, 80)), fl=OWN | LAND))                     # an in half: the shadow wiped in
    hold_b = prog2(track(OB, 5, w2(3)), fl=OWN)
    hold = c.add(hold_b)                                                    # done and owning: holds level 5 over OB
    split = c.add(prog2(track(rect(20, 300, 60, 40), 3, w2(3)), track(rect(400, 300, 60, 40), 4, w2(3)), fl=OWN))   # owned rect [20, 460) × [300, 340)
    mid = c.add(prog(track(rect(200, 305, 60, 20), 6, fillseg(3, 9))))      # inside split's owned rect, meeting neither track
    beside = c.add(prog(track(rect(200, 350, 60, 20), 7, fillseg(3, 10))))  # outside it
    ownA = c.add(prog2(track(rect(20, 380, 40, 30), 8, w2(3)), track(rect(300, 455, 40, 25), 9, w2(3)), fl=OWN))     # [20, 340) × [380, 480)
    ownB = c.add(prog2(track(rect(200, 300, 40, 30), 10, w2(3)), track(rect(400, 420, 40, 30), 11, w2(3)), fl=OWN))  # [200, 440) × [300, 450)
    out2 = c.add(prog2(track(OB, BD_NONE, pc(4, K_OLD, dx=-30, vacate=1)), fl=OWN))     # a new out half over OB
    ph_ob = c.add(prog(track(rect(380, 100, 40, 30), 12, fillseg(3, 13))))  # a phone PLAY meeting OB
    apart = c.add(prog(track(rect(560, 400, 60, 40), 6, fillseg(3, 2))))   # a phone PLAY apart from every owner
    band = c.add(prog(track(rect(120, 90, 120, 40), 2, drawrec(3, pic, 120, 0, pref0=pref(0, F_Y)))))   # a list program meeting OA
    own_pl = c.add(prog2(track((rect(100, 260, 80, 40), rect(160, 260, 80, 40)), 3, w2(3)), fl=OWN))   # owned: L [100, 180), R [160, 240); box [100, 240)
    m_left = c.add(prog(track(rect(110, 270, 30, 20), 6, fillseg(3, 9))))   # meets LEFT's owned rect only
    m_right = c.add(prog(track(rect(200, 270, 30, 20), 2, drawrec(3, pic, 200, 0, pref0=pref(0, F_Y)))))   # RIGHT's only (a list program)
    st = [arm(c)]
    t = 2000
    st += [step(tk(t), msg(batch(fill(rect(110, 90, 80, 40), 12), play(0, P, out_, 0x7001)))),   # 1  owns OA: step 1 from the panel's picture, the fill held; no END
           step(tk(t + 10), msg(batch(fill(rect(150, 120, 60, 30), 13), fill(rect(0, 420, 640, 20), 4)))),   # 2  k = 1 = d: no step; held in OA, shown outside
           step(tk(t + P), wake()),                                         # 3  step 2
           step(tk(t + 2 * P + 5), msg(batch(fill(rect(120, 150, 40, 20), 14)))),   # 4  k = 3 > d: this frame's present draws step 3, the fill held
           step(tk(t + 4 * P), wake()),                                     # 5  step 5 = T: done, owning (no LAND)
           step(tk(t + 4 * P + 10), msg(batch(fill(rect(200, 100, 30, 30), 3)))),   # 6  done and owning: held (present 1)
           step(tk(t + 4 * P + 20), msg(batch(fill(rect(0, 400, 640, 8), 5)))),     # 7  (present 2)
           step(tk(t + 4 * P + 30), msg(batch(fill(rect(130, 170, 50, 20), 9)))),   # 8  (present 3)
           step(tk(t + 4 * P + 40), msg(play(1, P, inl, 0x7002))),          # 9  closes slot 0 and holds OA again: the wipe starts from the out half's pose
           step(tk(t + 5 * P + 40), wake()),                                # 10 step 2: `new` read live
           step(tk(t + 5 * P + 50), msg(batch(fill(rect(250, 160, 40, 30), 1)))),   # 11 k = 2 = d: held, no step
           step(tk(t + 7 * P + 40), wake()),                                # 12 step 4 = T: LAND copies the shadow into OA; done, owns cleared; whole
           step(tk(t + 7 * P + 50), msg(batch(fill(rect(140, 100, 20, 20), 6), hint(100, 119))))]   # 13 nothing owns: the frame shows
    t = 3000
    st += play_to_end(0, hold, 0x7010, t, 3) + [                            # each end of the mark, the next present copying OB:
        step(tk(t + 2 * P + 10), msg(batch(fill(rect(370, 90, 40, 40), 11)))),   # held
        step(tk(t + 2 * P + 20), msg(close(0)))]                           # CLOSE (a done owner: no notify); its present copies OB
    t += 300
    st += play_to_end(0, hold, 0x7011, t, 3) + [
        step(tk(t + 2 * P + 10), msg(batch(fill(rect(380, 120, 40, 40), 12)))),
        step(tk(t + 2 * P + 20), msg(play(0, P, apart, 0x7012)))]           # a PLAY into its slot (apart from OB)
    t += 300
    st += play_to_end(1, hold, 0x7013, t, 3) + [
        step(tk(t + 2 * P + 10), msg(play(2, P, ph_ob, 0x7014)))]           # a close pass: a phone PLAY meeting OB
    t += 300
    st += play_to_end(1, hold, 0x7015, t, 3) + [step(tk(t + 2 * P + 10), msg(kf))]   # a live keyframe
    t += 300
    st += play_to_end(1, hold, 0x7016, t, 3) + [
        step(tk(t + 2 * P + 10), msg(cache_write2((hold, hold_b)))),        # a cache write over its program: closed, nothing presents
        step(tk(t + 2 * P + 20), msg(batch(fill(rect(0, 470, 8, 8), 1))))]  # the next present copies OB
    t += 300
    st += play_to_end(0, split, 0x7020, t, 3) + [
        step(tk(t + 2 * P + 10), msg(play(1, P, beside, 0x7021))),          # outside the owned rect: split keeps owning
        step(tk(t + 2 * P + 20), msg(play(2, P, mid, 0x7022)))]             # inside it, meeting neither track: closes split
    t += 300
    st += play_to_end(0, ownA, 0x7023, t, 3) + [
        step(tk(t + 2 * P + 10), msg(play(1, P, ownB, 0x7024))),            # two owners meet by their owned rects: ownA closed, ownB's rect held
        step(tk(t + 3 * P + 10), wake()), step(tk(t + 4 * P + 10), wake()),
        step(tk(t + 4 * P + 20), msg(close(0xFF)))]
    t += 400
    st += play_to_end(0, hold, 0x7030, t, 3) + [
        step(tk(t + 2 * P + 10), msg(play(1, P, out2, 0x7031))),            # a new out half over OB closes hold and holds OB: its base is hold's step T
        step(tk(t + 3 * P + 10), wake()), step(tk(t + 5 * P + 10), wake()), # to its T, one step folded
        step(tk(t + 5 * P + 20), msg(retarget(1))),                         # RETARGET of an OWN instance: 12 (§7.2)
        step(tk(t + 5 * P + 30), msg(close(0xFF)))]
    t += 400
    st += [step(tk(t), msg(bind_list(0, NONE, 8, 0, 10, 1)),
                msg(bind_set(0, ANY, UP, 0, 1, 2, P, band, [(K_PITCH, 90)])),        # y = c' × 10 + 90, slot 2
                msg(bind_set(1, RING, TAP, 0, 0, 2, P, NONE))),                     # report-only (one slot per list: 2)
           step(tk(t + 10), msg(play(0, P, out_, 0x7040))),                         # out_ owns OA; band meets it
           step(tk(t + 20), inp(RING, UP)),                                         # S 1: 19, nothing closed, fields 10–14 carried
           step(tk(t + 10 + P), wake()),                                            # out_ draws on: step 2
           step(tk(t + P + 20), msg(play(2, P, hold, 0x7041))),                     # an owner in the list's slot
           step(tk(t + P + 30), inp(RING, TAP)),                                    # S 2: report-only, not tested: outcome 0, nothing closed
           step(tk(t + P + 40), inp(TOUCH_R, UP, 30)),                              # S 3: the slot's owner in the close set: 19
           step(tk(t + 10 + 4 * P), wake()),                                        # out_'s T and hold's: both done and owning
           step(tk(t + 4 * P + 20), msg(commit(3, 3, 0, 0, band, (90,)))),         # a COMMIT's close pass meeting OA closes out_ as any other
           step(tk(t + 4 * P + 30), msg(close(0xFF)))]
    t += 400
    st += play_to_end(0, hold, 0x7050, t, 3) + [
        step(tk(t + 2 * P + 10), msg(batch(fill(rect(0, 440, 640, 10), 6), hint(440, 449)))),   # whole (the last step's mark)
        step(tk(t + 2 * P + 20), msg(batch(fill(rect(370, 100, 60, 10), 2), fill(rect(0, 100, 40, 10), 8), hint(100, 109)))),   # rows 100–109 with an owner open: OB's part keeps step T
        step(tk(t + 2 * P + 30), msg(close(0)))]
    t += 400
    st += play_to_end(0, hold, 0x7060, t, 3) + [
        step(tk(t + 2 * P + 10), msg(bytes([11]))),                          # mode 11: a release point, no notify
        step(tk(t + 2 * P + 20), {"lease": "acquire"}, flags(FULL5), *writes(c)),   # a fresh session, no frame yet
        step(tk(t + 2 * P + 30), msg(play(0, P, hold, 0x7061))),             # its present takes the stock path (no Damage frame): done, owning nothing
        step(tk(t + 2 * P + 40), msg(batch(fill(rect(0, 470, 8, 8), 3)))),   # the session's first frame: copied whole, OB included
        step(tk(t + 3 * P + 40), wake()),
        step(tk(t + 3 * P + 50), msg(kf))]
    t += 400
    st += play_to_end(0, hold, 0x7062, t, 3) + [
        step(tk(t + 2 * P + 10), {"lease": "release"}),                     # FB_RELEASE: ended, no notify
        step(stock()),
        step(tk(t + 2 * P + 20), {"lease": "acquire"}, flags(FULL5), msg(kf), *writes(c), tk(t + 2 * P + 20), msg(play(0, P, out_, 0x7063))),
        step(tk(t + 3 * P + 20), wake())]
    # §7.3 (b24a110) "Meeting: one box over both lenses ... Every meeting decision uses the box — a PLAY's and a
    # PLAY-AFTER's close pass, a COMMIT's close pass, close-on-meet's area test, and the fire's close set (and so its 19) —
    # so both lenses decide alike"; "The held copy and LAND use each lens's own owned rect"
    t += 400
    st += play_to_end(0, own_pl, 0x7070, t, 3) + [
        step(tk(t + 2 * P + 10), msg(batch(fill(rect(100, 262, 140, 30), 13)))),   # held per lens: LEFT's rect on LEFT, RIGHT's on RIGHT
        step(tk(t + 2 * P + 20), msg(play(1, P, m_left, 0x7071)))]               # meets LEFT's owned rect only: closed on both lenses
    t += 300
    st += play_to_end(0, own_pl, 0x7072, t, 3) + [
        step(tk(t + 2 * P + 10), page(True), msg(bind_list(1, NONE, 8, 4, 10, 2)),
             msg(bind_set(2, ANY, DOWN, 1, -1, 3, P, m_right, [(K_ARG, 270)]))),   # the lowest DOWN entry; slot 3
        step(tk(t + 2 * P + 20), inp(RING, DOWN)),                                 # its area meets RIGHT's owned rect only: 19 on both
        step(tk(t + 2 * P + 30), msg(close(0xFF)))]
    vec("v5-own", st)

    # ---------------------------------------------------------------------------------------------------------------
    # v5-new — §7.3 "The source `new` (DRAW, REVEAL, PIECES kind 4) and the backdrop `new`: this lens's track area of the
    # live shadow, read by the copy hook under the engine word at every step it draws, as a source of the area's width and
    # height whose pixel (c, r) is the shadow's pixel (l + c, t + r) of this lens's rect. A DRAW of `new` at rest (x, y =
    # the rect's corner, no move, level 15, unscaled) paints the shadow's own picture"; §7.2 the none-backdrop rule's
    # exception "a REVEAL in an OWN program with a non-decreasing ease, whose uncovered part is the panel's picture the
    # instance keeps"; "`new` ... only in an OWN program" (5). Each PLAY rides a batch whose fills land in the shadow
    # first and are held by the owner, so only a step reading the live shadow shows them.
    c = Cache2()
    pic = c.add(image2(40, 30, pattern(40, 30, 521)))
    NA, PL = rect(40, 60, 160, 100), (rect(300, 200, 160, 100), rect(316, 200, 160, 100))
    nrest = c.add(prog2(track(NA, 0, dnew(3, 40, 60)), fl=OWN))                                   # at rest
    progs = [(c.add(prog2(track(NA, 3, dnew(5, 40, 60, dx=-60, dy=30, lvl=(2, 15))), fl=OWN)), 5),    # moved, ramped
             (c.add(prog2(track(NA, 6, dnew(4, 40, 60, dx=20, opt=0x30)), fl=OWN)), 4),              # transparent, inverse
             (c.add(prog2(track(NA, BD_NONE, r2(4, TOP, K_NEW, 40, 60)), fl=OWN)), 4),               # a wipe; none: the panel kept
             (c.add(prog2(track(NA, 4, r2(5, SLIDE | LEFT, K_NEW, 40, 60, ease=EASE_OUT)), fl=OWN | LAND)), 5),   # a slide, LAND
             (c.add(prog2(track(NA, BD_NONE, r2(4, BOTTOM, K_NEW, 40, 60, ease=EASE_OUT)), fl=OWN)), 4),
             (c.add(prog2(track(NA, BD_NEW, d2(4, pic, 50, 70, dx=100)), fl=OWN)), 4),               # backdrop `new` under a record
             (c.add(prog2(track(PL, BD_NEW, dnew(4, 300, 200, xR=316, dx=30, dxR=10)), fl=OWN)), 4), # per lens
             (c.add(prog2(track(PL, BD_NONE, r2(4, RIGHT, K_NEW, 300, 200, xR=316)), fl=OWN)), 4),
             (c.add(prog2(track(NA, 0, dnew(4, 40, 60, dy=-40)), fl=OWN | LAND)), 4)]
    nout = c.add(prog2(track(NA, 0, dnew(3, 40, 60)), bad=True))                                  # `new` outside OWN: 5
    nrw = c.add(prog2(track(NA, BD_NONE, w2(1), r2(3, TOP, K_NEW, 40, 60), w2(1)), fl=OWN))       # one REVEAL between WAITs, none: taken
    n2r = c.add(prog2(track(NA, BD_NONE, r2(2, TOP, K_NEW, 40, 60), r2(2, LEFT, K_NEW, 40, 60)), fl=OWN))   # two REVEALs in one such track: 2
    st = [arm(c), step(tk(1500), msg(fill_pair(rect(310, 210, 60, 40), rect(380, 230, 60, 40), 15)))]   # the two shadows differ in PL
    t = 2000
    st += [step(tk(t), msg(batch(fill(rect(50, 70, 60, 40), 12), play(0, P, nrest, 0x7101)))),   # at rest: step 1 is the shadow's picture
           step(tk(t + P), wake()),
           step(tk(t + 2 * P - 4), msg(batch(fill(rect(120, 100, 50, 40), 5)))),               # into the shadow, held (k = 2 = d)
           step(tk(t + 2 * P), wake()),                                                         # step 3 reads the live shadow: it shows
           step(tk(t + 2 * P + 10), msg(close(0))),
           step(tk(t + 2 * P + 20), msg(play(1, P, nout, 0x7102)))]
    for i, (p_, T_) in enumerate(progs):
        t = 2400 + 400 * i
        st += [step(tk(t), msg(batch(fill(rect(60 + 7 * i, 80, 40, 20), 3 + i),
                                     fill_pair(rect(320 + 3 * i, 220, 30, 30), rect(400, 240 - 3 * i, 30, 30), 1 + i),
                                     play(i % 4, P, p_, 0x7110 + i))))]
        st += [step(tk(t + k * P), wake()) for k in range(1, T_)]
        st += [step(tk(t + T_ * P), msg(close(i % 4)))]
    t += 400                                                                     # §7.2 (b24a110): the none-backdrop rule's exception
    st += [step(tk(t), msg(batch(fill(rect(70, 90, 50, 30), 8), play(0, P, nrw, 0x7120)))),     # taken: WAIT, REVEAL, WAIT
           *[step(tk(t + k * P), wake()) for k in range(1, 5)],
           step(tk(t + 5 * P), msg(play(1, P, n2r, 0x7121))),                      # two REVEAL segments: 2
           step(tk(t + 5 * P + 10), msg(close(0xFF)))]
    vec("v5-new", st)

    # ---------------------------------------------------------------------------------------------------------------
    # v5-pieces-old — §7.4 "Layer old (kind 0; in place; OWN programs only) ... From base step b to step k, with Δ_p =
    # D_p(k) − D_p(b) and the base rect ... every pixel q of the area becomes G(b → k) of the base picture's pixel at q −
    # Δ_p ... otherwise `src`"; "G(b → k) = g_k ∘ … ∘ g_(b+1) ... g_t(v) = 0 when L_(t−1) = 0, else min(15, ⌊v·L_t /
    # L_(t−1)⌋). The vacate level is not passed through G"; the models (uniform with aux0 0x0101, radial with dx = dy = 0,
    # random with gw = 1 and dy = 0), the factor; §7.2 "The base ... b = d", "no later track of the program meets its area
    # while it moves pixels (12)", the still forms. Every step drawn here; the folds are the pair vectors below.
    c = Cache2(); fnt, _ = font(c, 531)
    alpha = c.add(alphabet(range(65, 91)))
    ctab = c.add(cell_table([c.add(cell_list(alpha, 1, [cell(80 + 14 * i, 60, 12, 16, fnt, 65 + i, 70 + i, 2, 12) for i in range(6)]))]))
    PA = rect(300, 40, 200, 100)
    po = [(c.add(prog2(track((rect(60, 40, 200, 100), rect(76, 40, 200, 100)), BD_NONE,
                             pc(5, K_OLD, dx=40, dxR=56, dy=10, vacate=3)), fl=OWN)), 5),                # uniform, per-lens dx, vacate 3
          (c.add(prog2(track(PA, BD_NONE, pc(5, K_OLD, dx=33, vacate=9)), fl=OWN)), 5),                # Δ 7, 6, 7, 6, 7: odd and even
          (c.add(prog2(track(rect(60, 180, 240, 120), BD_NONE, pc(6, K_OLD, grid=(4, 3), model=RADIAL, x=180, y=240,
                                                                    f=(0, 2048), vacate=1)), fl=OWN)), 6),   # radial 4×3 about the centre
          (c.add(prog2(track(rect(320, 180, 288, 144), BD_NONE, pc(6, K_OLD, grid=(8, 6), model=RADIAL, x=320, y=180,
                                                                     f=(0, 3000), ease=EASE_OUT)), fl=OWN)), 6),   # radial 8×6 about a corner
          (c.add(prog2(track(rect(60, 320, 300, 120), BD_NONE, pc(5, K_OLD, grid=(1, 12), model=RANDOM, dx=80,
                                                                    seed=0x1234, vacate=4)), fl=OWN)), 5),   # random slices (gw 1)
          (c.add(prog2(track(rect(380, 340, 200, 100), BD_NONE, pc(4, K_OLD, dx=-24, lvl=(15, 4), vacate=12)), fl=OWN)), 4),   # G dims; the vacate 12 not dimmed
          (c.add(prog2(track(rect(380, 340, 200, 100), BD_NONE, pc(4, K_OLD, dx=-24, lvl=(6, 0), vacate=7)), fl=OWN)), 4),     # a level that reaches 0 (g_t = 0 after it)
          (c.add(prog2(track(rect(60, 40, 200, 100), BD_NONE, pc(5, K_OLD, lvl=(15, 5))),
                       track(rect(80, 56, 90, 24), BD_NONE, gly(5, CELLS, ctab, 0, seed=4)), fl=OWN)), 5),   # a dim with cells over it
          (c.add(prog2(track(PA, BD_NONE, w2(2), pc(3, K_OLD, dx=-20, dy=6, vacate=5)), fl=OWN)), 5)]       # a leading WAIT: b = 0 until it moves
    bad_po = [c.add(prog2(track(PA, BD_NONE, pc(4, K_OLD, **{"dx": 20, **kw})), fl=OWN, bad=bd)) for kw, bd in [
        (dict(f=(100, 4096)), False),                      # f0 ≠ 0 on layer old: 12
        (dict(grid=(4, 3), model=RADIAL, dx=0, f=(0, -100)), False),   # radial f1 < 0: 12
        (dict(grid=(2, 4), model=RANDOM), False),          # random with gw 2: 12
        (dict(grid=(1, 4), model=RANDOM, dy=5), False),    # random with dy: 12
        (dict(vacate=16), False),                          # the vacate level 16: 12
        (dict(grid=(0, 4), model=RANDOM), False),          # gw 0: 12
        (dict(grid=(65, 1), model=RADIAL, dx=0), False),   # gw 65: 12
        (dict(grid=(16, 17), model=RADIAL, dx=0), False),  # 272 pieces: 12
        (dict(model=3), True),                             # model 3: 5
        (dict(model=UNIFORM | 0x04), True),                # aux1 bit 2: 5
        (dict(grid=(2, 1)), False),                        # uniform with aux0 0x0102: 12
        (dict(grid=(2, 2), model=RADIAL), False),          # radial with dx: 12
        (dict(stagger=1), False)]]                         # stagger on layer old: 12
    st = [arm(c)]
    t = 2000
    for i, (p_, T_) in enumerate(po):
        st += play_to_end(i % 2, p_, 0x7200 + i, t, T_)
        t += 300
    st += [step(tk(t + 10 * i), msg(play(2, P, b_, 0x7220 + i))) for i, b_ in enumerate(bad_po)]
    vec("v5-pieces-old", st)

    # ---------------------------------------------------------------------------------------------------------------
    # v5-pieces-new — §7.4 "Layer new (kind 4; OWN programs only). Over the track's backdrop, pieces in index order: the
    # shadow's home rect of piece p (this lens) drawn at its home rect moved by D_p at its local step (t_p under stagger),
    # clipped to the track's area, through the level ramp ...; a source pixel of 0 is not drawn when opt bit 4 is set.
    # `src` must be 0 (12)"; "stagger (layer new only ...): n' = steps − stagger ≥ 1 (12); δ_p = H3(seed, p, 1) mod
    # (stagger + 1); ... ease 2: the table's n must equal n', else 5 ... when stagger ≥ steps only that n comparison is
    # skipped — the table is still walked (inside the cache, E[n] = 4096 at its own n, 5) — and stagger ≥ steps is 12 at
    # the values position"; "random: r = H3(seed, p, 0), or H3(seed, p, t) with opt bit 6 ... Opt bit 6's t is the
    # segment's local step, not t_p"; §7.2 "The seed ... exclusive-or the instance's tag when opt bit 7 is set".
    c = Cache2()
    stab = c.add(easing([600, 1500, 2600, 3500, 4096]))          # n 5 = 8 − 3
    stab4 = c.add(easing([900, 2100, 3300, 4096]))               # n 4
    badE = c.add(easing([900, 2100, 3300, 4000]))                # E[n] 4000
    pn = [(c.add(prog2(track(rect(40, 40, 240, 180), 1, pc(6, K_NEW, grid=(4, 3), model=RANDOM, dx=120, dy=60,
                                                           f=(4096, 0), lvl=(6, 15), seed=77)), fl=OWN)), 6),   # tiles assembling: f0 4096 → f1 0
          (c.add(prog2(track(rect(300, 40, 240, 120), 2, pc(8, K_NEW, grid=(6, 4), model=RANDOM, stagger=3, dx=-80, dy=40,
                                                            f=(4096, 0), ease=TABLE, table=stab, seed=9)), fl=OWN)), 8),   # stagger 3, the table at n − 3
          (c.add(prog2(track(rect(40, 240, 200, 100), BD_NEW, pc(5, K_NEW, grid=(5, 4), model=RANDOM, dx=30, dy=30,
                                                                 opt=0x40, seed=3)), fl=OWN)), 5),   # reseeded every step (local step)
          (c.add(prog2(track(rect(260, 240, 160, 100), 7, pc(4, K_NEW, grid=(2, 2), model=RADIAL, x=340, y=290,
                                                             opt=0x10)), fl=OWN)), 4),   # transparency
          (c.add(prog2(track(rect(260, 360, 160, 100), 7, pc(4, K_NEW, grid=(4, 2), model=RADIAL, x=340, y=410,
                                                             f=(0, -2048))), fl=OWN)), 4),   # layer new radial inward (no f1 ≥ 0 rule)
          (c.add(prog2(track((rect(440, 240, 160, 100), rect(456, 240, 160, 100)), 3, pc(4, K_NEW, dx=20, dxR=-20, dy=8)),
                       fl=OWN)), 4),                                                         # per lens, uniform
          (c.add(prog2(track(rect(40, 360, 200, 100), 0, pc(5, K_NEW, grid=(3, 3), model=RANDOM, dx=50, dy=50, opt=0x80,
                                                            seed=0x5A5A)), fl=OWN)), 5)]     # the tag mixed into the seed
    bad_pn = [c.add(prog2(track(rect(300, 360, 120, 80), 2, pc(n_, K_NEW, grid=(2, 2), model=RANDOM, dx=10, **kw)), fl=OWN, bad=bd))
              for n_, kw, bd in [
                  (4, dict(stagger=4), False),                                  # stagger ≥ steps: 12
                  (4, dict(stagger=5, ease=TABLE, table=PAST), True),           # stagger ≥ steps, the table outside the cache: 5
                  (4, dict(stagger=6, ease=TABLE, table=badE), True),           # stagger ≥ steps, E[n] ≠ 4096: 5
                  (4, dict(stagger=4, ease=TABLE, table=stab4), False),         # stagger ≥ steps, a table of any n: 12
                  (6, dict(stagger=2, ease=TABLE, table=stab), True),           # stagger 2 of 6: the table's n 5 ≠ 4: 5
                  (4, dict(vacate=1), False)]]                                  # src ≠ 0: 12
    st = [arm(c)]
    t = 2000
    for i, (p_, T_) in enumerate(pn):
        st += play_to_end(i % 2, p_, 0x7300 + i, t, T_)
        t += 400
    st += [step(tk(t + 10 * i), msg(play(2, P, b_, 0x7320 + i))) for i, b_ in enumerate(bad_pn)]
    vec("v5-pieces-new", st)

    # ---------------------------------------------------------------------------------------------------------------
    # v5-shrink — §7.5 "SHRINK (in place; kind 0; OWN programs only). With Wa, Ha the track's area size: w0 = w1 = 0 leaves
    # the width unscaled ..., else 0 ≤ w1 ≤ w0 ≤ Wa (12) ...; at instance step 0 the size is (Wa, Ha). `src` the vacate
    # level (0..15, 12). Anchor bits 0–1 vertical ..., bits 2–3 horizontal"; "The drop order of a size n ≥ 1 ... (n = 5:
    # 0, 4, 2, 1, 3; n = 6: 0, 4, 2, 1, 5, 3; n = 12: ...). The rows kept at height H are the first H indices of the order
    # for Ha ... (nested as the size falls)"; "Placement ... a + r, with a = 0 (top), (Ha − H) >> 1 (centre) or Ha − H
    # (bottom)"; "From base step b to step k".
    c = Cache2()
    sp_ = [(c.add(prog2(track(rect(40, 40, 120, 12), BD_NONE, sh(6, h=(12, 0), vacate=2)), fl=OWN)), 6),        # rows, n = 12, to 0
           (c.add(prog2(track(rect(200, 40, 6, 60), BD_NONE, sh(6, w=(6, 1), vacate=3)), fl=OWN)), 6),         # columns, n = 6
           (c.add(prog2(track(rect(260, 40, 200, 120), BD_NONE, sh(6, w=(200, 40), h=(120, 30), an=anc(1, 1), lvl=(15, 8))), fl=OWN)), 6),   # both, centre, G
           (c.add(prog2(track(rect(40, 70, 100, 1), BD_NONE, sh(2, h=(1, 0), vacate=9)), fl=OWN)), 2),         # n = 1
           (c.add(prog2(track(rect(40, 80, 100, 5), BD_NONE, sh(4, h=(5, 1), vacate=9)), fl=OWN)), 4),         # n = 5
           (c.add(prog2(track(rect(40, 90, 100, 6), BD_NONE, sh(5, h=(6, 1), an=anc(2, 0), vacate=9)), fl=OWN)), 5),   # n = 6, bottom
           (c.add(prog2(track(rect(480, 40, 5, 40), BD_NONE, sh(5, w=(5, 0), h=(40, 0), an=anc(1, 2), vacate=14)), fl=OWN)), 5),   # both to 0
           (c.add(prog2(track(rect(40, 180, 100, 40), BD_NONE, w2(2), sh(3, w=(60, 20), vacate=6)), fl=OWN)), 5)]   # w0 < Wa after a WAIT
    anchors = [c.add(prog2(track(rect(20 + 70 * i, 400, 60, 50), BD_NONE, sh(3, w=(60, 20), h=(50, 16), an=anc(i // 3, i % 3), vacate=i)),
                           fl=OWN)) for i in range(9)]                                # every anchor pair
    bad_sh = [c.add(prog2(track(rect(300, 200, 100, 40), bd_, sh(4, **kw)), fl=f_, bad=b_)) for bd_, kw, f_, b_ in [
        (BD_NONE, dict(w=(50, 60)), OWN, False),           # w1 > w0: 12
        (BD_NONE, dict(w=(101, 20)), OWN, False),          # w0 > Wa: 12
        (BD_NONE, dict(h=(0, 3)), OWN, False),             # h0 0, h1 3: 12
        (BD_NONE, dict(h=(41, 3)), OWN, False),            # h0 > Ha: 12
        (BD_NONE, dict(h=(40, 3), vacate=16), OWN, False), # vacate 16: 12
        (BD_NONE, dict(h=(40, 3), an=anc(0, 3)), OWN, True),   # horizontal anchor 3: 5
        (BD_NONE, dict(h=(40, 3), kind=1), OWN, True),     # kind 1: 5
        (BD_NONE, dict(h=(40, 3), xL=4), OWN, True),       # an unused field: 5
        (2, dict(h=(40, 3)), OWN, True),                   # a backdrop: 5
        (BD_NONE, dict(h=(40, 3)), 0, True)]]              # not OWN: 5
    st = [arm(c)]
    t = 2000
    for i, (p_, T_) in enumerate(sp_):
        st += play_to_end(i % 2, p_, 0x7400 + i, t, T_)
        t += 300
    for g in range(0, 9, 4):                                                        # four at a time, one in each slot
        st.append(step(*[o for k, a_ in enumerate(anchors[g:g + 4]) for o in (tk(t), msg(play(k, P, a_, 0x7410 + g + k)))]))
        st += [step(tk(t + P), wake()), step(tk(t + 2 * P), wake())]
        t += 200
    st += [step(tk(t + 10 * i), msg(play(0, P, b_, 0x7430 + i))) for i, b_ in enumerate(bad_sh)]
    vec("v5-shrink", st)

    # ---------------------------------------------------------------------------------------------------------------
    # the fold pairs — §7.2 "The base ... an in-place track's result at step k is a function of the base picture, b and
    # k ..., equal to the result of drawing every step between. Fold-independence with later tracks: the pixels a later
    # track painted at step t inside the in-place track's area are part of the base at t + 1"; §7.13 "two wake schedules
    # that draw different steps between the same two steps end with the same P"; §7.11 "a cell that settles to a
    # non-zero code over a dim keeps its fill, or settles to 0". Each pair: the same messages; "-a" wakes at every step,
    # "-b" at a few (the rest folded); both end at step T, and their final P must be equal on both sides (the "pair" key).
    def fold_pair(name, build, T, wakes_b):
        for sfx, wk in (("a", range(1, T)), ("b", wakes_b)):
            c, p_ = build()
            st = [arm(c), step(tk(1500), msg(batch(fill(rect(0, 0, 640, 8), 3), fill(rect(0, 470, 640, 10), 12))))]
            st += [step(tk(2000), msg(play(0, P, p_, 0x7F01)))] + [step(tk(2000 + k * P), wake()) for k in wk]
            vec(f"{name}-{sfx}", st, pair=name)
    def fb_pieces():
        c = Cache2()
        return c, c.add(prog2(track((rect(40, 40, 400, 300), rect(56, 40, 400, 300)), BD_NONE,
                                    pc(8, K_OLD, grid=(4, 3), model=RADIAL, x=240, xR=256, y=190, f=(0, 3000), lvl=(15, 7),
                                       vacate=2, ease=EASE_OUT)), fl=OWN))
    def fb_shrink():
        c = Cache2()
        return c, c.add(prog2(track(rect(100, 60, 300, 200), BD_NONE, sh(8, w=(300, 30), h=(200, 20), an=anc(1, 2), vacate=5,
                                                                           lvl=(15, 9))), fl=OWN))
    def fb_rain():
        c = Cache2(); fnt, _ = font(c, 541)
        rr = c.add(rain_rec(30, 5, 10, 14, fnt, c.add(alphabet(range(48, 58))), head=15, tail=4, reveal=2, fill=1, spread=6, flicker=3))
        return c, c.add(prog2(track(rect(20, 30, 300, 200), BD_NONE, gly(9, RAIN, rr, x=3, seed=0xC0DE)), fl=OWN))
    def fb_dim():
        c = Cache2(); fnt, _ = font(c, 545)
        al = c.add(alphabet(range(65, 80)))
        ct = c.add(cell_table([c.add(cell_list(al, 2, [cell(60 + 16 * i, 80, 13, 16, fnt, 65 + i, 0, 1 + i % 4, 15) for i in range(10)]))]))
        return c, c.add(prog2(track(rect(40, 60, 300, 120), BD_NONE, pc(8, K_OLD, lvl=(15, 3))),
                              track(rect(50, 70, 280, 40), BD_NONE, gly(8, CELLS, ct, 0, seed=8)), fl=OWN))
    fold_pair("v5-fold-pieces", fb_pieces, 8, [3, 6, 7])
    fold_pair("v5-fold-shrink", fb_shrink, 8, [2, 7])
    fold_pair("v5-fold-rain", fb_rain, 9, [4, 8])
    fold_pair("v5-fold-dim", fb_dim, 8, [1, 5, 7])

    # ---------------------------------------------------------------------------------------------------------------
    # v5-draw2 — §7.5 "DRAW, version 2 — two axes. w0, w1 both 0 (unscaled) or both 1..2048 with every step's W(t) in
    # 1..2048 (12; one 0 and one not: 12), W(t) eased as H. Output column c of a drawn width W takes source column ⌊(2c +
    # 1)·w / (2W)⌋ ...; the horizontal anchor places the drawn rect at x (left), x + ((w − W) >> 1) (centre) or x + w − W
    # (right) ... Sources: a record ..., its source window, `new`; `self` only unscaled in both axes (12). RETARGET's
    # restart path clamps W to 1..2048 and restarts it from the width the track last drew"; "The source window (DRAW v2
    # of a record, kind 0): aux0 the window's first source row, aux1 its height in rows (both after substitution; aux1 =
    # 0 means the whole record, and then aux0 must be 0). The window must lie inside the record, aux0 + aux1 ≤ its height
    # computed in 32 bits (5, decided in the walk at the record); with kind 2 or 4, aux0 and aux1 must be 0 (5) ... With a
    # kind-2 parameter (c'·pitch + arg, §6.2) substituted into aux0 (pref 16), a bound program draws one row of a strip";
    # §7.12 "At a RETARGET the start covers every state the restarted steps can paint" (the fast path's twice-run).
    c = Cache2()
    pic = c.add(image2(40, 30, pattern(40, 30, 561)))
    strip = c.add(image2(60, 200, runs_pattern(60, 200, 562)))
    ov = c.add(easing([900, 2300, 3500, 4300, 4620, 4600, 4420, 4096]))   # an overshoot past E 4096
    dw = [c.add(prog2(track(rect(20 + 150 * k, 40, 140, 80), 2, d2(4, pic, 30 + 150 * k, 50, w0=10, w1=80, anchor=anc(0, k)))))
          for k in range(3)]                                                         # W 10 → 80 at each horizontal anchor
    dw += [c.add(prog2(track(rect(20 + 150 * k, 130, 140, 110), 3, d2(5, pic, 40 + 150 * k, 140, dx=20, w0=80, w1=20, h0=15, h1=60,
                                                                     anchor=anc(k, k), lvl=(6, 15), ease=EASE_OUT))))
           for k in range(3)]                                                        # W with H: (top, left), (centre, centre), (bottom, right)
    dw += [c.add(prog2(track(rect(20, 250, 600, 30), 4, d2(3, pic, 20, 250, w0=1, w1=1)),                    # W = 1
                       track(rect(20, 285, 600, 30), 4, d2(3, pic, 20, 285, w0=40, w1=40)),                  # W = w
                       track(rect(20, 320, 600, 30), 4, d2(3, pic, 20, 320, w0=80, w1=80, opt=0x10)),        # 2w, transparent
                       track(rect(20, 355, 600, 30), 4, d2(3, pic, -700, 355, w0=2048, w1=2048, opt=0x20)))),   # 2048, clipped; inverse
           c.add(prog2(track(rect(300, 400, 200, 70), 2, dnew(4, 300, 400, w0=200, w1=50, h0=70, h1=30, anchor=anc(1, 1))), fl=OWN)),   # W and H on `new`
           c.add(prog2(track(rect(60, 400, 200, 70), BD_NEW, dnew(4, 60, 400, w0=100, w1=400, anchor=anc(0, 2))), fl=OWN | LAND))]   # `new` widened, right anchor
    selfw = c.add(prog2(track(rect(520, 400, 60, 40), 2, d2(4, 0, 520, 400, kind=K_SELF, w0=20, w1=20))))   # `self` scaled: 12
    rtw = c.add(prog2(track(rect(20, 300, 160, 120), 4, d2(8, pic, 30, 300, ease=TABLE, table=ov, pref0=pref(0, F_W0), pref1=pref(1, F_W1)))))
    unseen = c.add(prog2(track(rect(400, 300, 60, 60), 2, d2(6, strip, 400, 300, pref0=pref(0, F_DY)))))   # the strip panned by P0
    win = c.add(prog2(track(rect(200, 430, 60, 10), 2, d2(4, strip, 200, 430, aux1=10, pref0=pref(0, F_AUX0)))))   # one 10-row window
    winsc = c.add(prog2(track(rect(280, 400, 120, 40), 2, d2(4, strip, 280, 400, aux1=10, h0=10, h1=30, w0=60, w1=120,
                                                             anchor=anc(1, 1), pref0=pref(0, F_AUX0)))))   # the window scaled both ways
    wlast = c.add(prog2(track(rect(500, 40, 60, 40), 2, d2(4, pic, 500, 40, aux0=20, aux1=10))))         # the record's last rows: taken
    bad_w = [c.add(prog2(track(rect(500, 100, 100, 60), bd_, seg_), fl=f_, bad=b_)) for bd_, seg_, f_, b_ in [
        (2, d2(4, pic, 500, 100, aux0=3), 0, True),                                  # aux1 0 with aux0 ≠ 0: 5
        (2, d2(4, pic, 500, 100, aux0=21, aux1=10), 0, True),                        # past the record (31 > 30): 5
        (2, d2(4, pic, 500, 100, aux0=0xFFF0, aux1=0x20), 0, True),                  # past it in 32 bits (16 bits would wrap to 16): 5
        (2, dnew(4, 500, 100, aux1=5), OWN, True),                                   # a window on `new`: 5
        (2, d2(4, 0, 500, 100, kind=K_SELF, aux0=1), 0, True),                       # on `self`: 5
        (2, d2(4, pic, 500, 100, w0=0, w1=50), 0, False),                            # one 0 and one not: 12
        (2, d2(4, pic, 500, 100, w0=2049, w1=2049), 0, False),                       # 2049: 12
        (2, d2(8, pic, 500, 100, w0=100, w1=2000, ease=TABLE, table=ov), 0, False),  # an eased W past 2048: 12
        (2, d2(4, pic, 500, 100, w0=40, w1=40, anchor=anc(0, 3)), 0, True)]]          # horizontal anchor 3: 5
    wcap = c.reserve(cap_total((60, 40)) + cap_total((100, 60)))
    st = [arm(c)]
    t = 2000
    for i, p_ in enumerate(dw):
        st += play_to_end(i % 4, p_, 0x7500 + i, t, (4, 4, 4, 5, 5, 5, 3, 4, 4)[i])
        t += 300
    st += [step(tk(t), msg(play(0, P, selfw, 0x7510, wcap))),                       # `self` scaled: 12
           step(tk(t + 10), msg(play(1, P, rtw, 0x7511, P=(100, 100)))), step(tk(t + 10 + P), wake()),
           step(tk(t + 10 + 2 * P), msg(retarget(1, (2040, 2040)))),                # the restart passes 2048: clamped
           step(tk(t + 10 + 6 * P), wake()), step(tk(t + 10 + 9 * P), wake()),
           step(tk(t + 10 + 10 * P), msg(retarget(1, (5, 5)))),                     # the restart passes 1: clamped
           step(tk(t + 10 + 14 * P), wake()), step(tk(t + 10 + 17 * P), wake())]
    t += 1000
    st += [step(tk(t), msg(play(2, P, unseen, 0x7512, P=(-20,)))), step(tk(t + P), wake()),   # rows 0..~66 of the strip
           step(tk(t + P + 5), msg(retarget(2, (-130,)))),                         # its restart shows rows the opening steps never did
           step(tk(t + 2 * P + 5), wake()), step(tk(t + 6 * P + 5), wake())]
    t += 400
    st += [step(tk(t + 10 * i), msg(play(3, P, b_, 0x7520 + i))) for i, b_ in enumerate(bad_w)]
    st += [step(tk(t + 100), msg(play(3, P, wlast, 0x7530)))]
    t += 300
    st += [step(tk(t), msg(bind_list(0, NONE, 24, 0, 10, 1)),
                msg(bind_set(0, ANY, UP, 0, 1, 1, P, win, [(K_PITCH, 0)])),         # aux0 = c' × 10: the row c'
                msg(bind_set(1, ANY, DOWN, 0, -1, 1, P, winsc, [(K_PITCH, 0)])))]   # scaled, the same slot
    st += [step(tk(t + 10), inp(RING, UP)), step(tk(t + 10), wake()),             # c' 1: rows 10..19
           step(tk(t + 20), inp(RING, UP)), step(tk(t + 20), wake()),             # c' 2
           step(tk(t + 30), inp(RING, DOWN)), step(tk(t + 30), wake()),           # c' 1, scaled
           step(tk(t + 40), msg(bind_list(0, NONE, 24, 17, 10, 2))),               # the cursor 1 + 17 = 18
           step(tk(t + 50), inp(RING, UP)), step(tk(t + 50), wake()),             # c' 19: rows 190..199, the record's last
           step(tk(t + 60), inp(RING, UP)),                                       # c' 20: rows 200..209 past the record: 5
           step(tk(t + 60 + 3 * P), wake())]
    vec("v5-draw2", st)

    # ---------------------------------------------------------------------------------------------------------------
    # v5-cells — §7.6 "Kind 0 CELLS. src names a cell table ...; aux0 (after substitution) is the index; an index at or
    # past n is 5 ... The cell list ...; old and new 0 or 32..255; fill 0..15 or 0xFF (none); the cell's opt: level bits
    # 0–3, bit 5 inverse, bits 4, 6 and 7 zero ... The alphabet ... Any of these out of its range, or outside the cache: 5
    # ... With n the segment's steps after substitution and N = noise, n − N ≥ 1 (12). The schedule of cell c: s_c = 1 +
    # (H2(seed, c) mod (n − N)); at local step t the cell shows its old code for t < s_c, alphabet[H3(seed, c, t) mod
    # n_alpha] for s_c ≤ t < s_c + N, its new code from s_c + N on. Each step, cells in list order: when fill is not
    # 0xFF, the box ... is filled with fill unless t ≥ s_c + N and new ≠ 0 (by phase, not by the code shown); then, unless
    # the code shown is 0 or 32, its glyph is drawn ... through the cell's LUT make_lut((opt & 0x20) | ⌊(opt & 15)·lvl(t)
    # / 15⌋)"; "Positions ... offsets"; "Read range: ... A cache write that meets it closes the instance"; §7.8 SET's kind 3.
    c = Cache2(); fnt, gl = font(c, 551)
    alpha = c.add(alphabet(range(65, 73)))
    cl0 = c.add(cell_list(alpha, 0, [cell(50 + 14 * i, 50, 12, 16, fnt, 65 + i, 80 + i, 2, 15) for i in range(10)]))   # N = 0
    cl5 = c.add(cell_list(alpha, 5, [cell(50 + 14 * i, 120, 12, 16, fnt, 48 + i, 97 + i, 3, 10) for i in range(8)]))    # N 5 of n 6
    clp = c.add(cell_list(alpha, 2, [cell(60, 190, 14, 18, fnt, 65, 32, 4),        # settles to 32: by phase, not filled; 32 draws nothing
                                     cell(80, 190, 14, 18, fnt, 66, 0, 4),         # settles to 0: filled to the end
                                     cell(100, 190, 14, 18, fnt, 0, 67, 0xFF),     # old 0: nothing drawn; fill none
                                     cell(120, 190, 14, 18, fnt, 32, 68, 6, 0x28), # old 32; inverse at level 8
                                     cell(140, 190, 14, 18, fnt, 69, 70, 0, 7)]))  # fill 0 (a level, not none)
    ctab = c.add(cell_table([cl0, cl5, clp]))
    pcl = [(c.add(prog2(track(rect(40, 40, 200, 60), 1, gly(6, CELLS, ctab, 0, seed=11)))), 6),                 # not OWN: END written
           (c.add(prog2(track(rect(40, 110, 200, 60), 1, gly(6, CELLS, ctab, 1, seed=12, lvl=(4, 15))))), 6),   # n = N + 1; the ramp
           (c.add(prog2(track(rect(40, 180, 200, 60), 9, w2(6)),
                        track(rect(50, 186, 140, 30), BD_NONE, gly(6, CELLS, ctab, 2, seed=13)))), 6),            # the fill by phase
           (c.add(prog2(track(rect(300, 40, 200, 60), BD_NEW, gly(5, CELLS, ctab, 0, x=250, y=-10, seed=14)), fl=OWN)), 5),   # over backdrop `new`
           (c.add(prog2(track((rect(300, 120, 200, 60), rect(316, 120, 200, 60)), 3,
                              gly(5, CELLS, ctab, 0, x=250, xR=266, y=70, dx=10, dxR=-10, dy=4, seed=15, opt=0x80)))), 5)]   # per lens, moving, the tag mixed
    pk3 = c.add(prog2(track(rect(300, 300, 200, 60), 1, gly(4, CELLS, ctab, 0, x=250, y=250, pref0=pref(0, F_AUX0)))))   # the index by kind 3
    def bcell(**kw):
        d = dict(bx=60, by=50, bw=12, bh=16, font=fnt, old=65, new=66, fill=2, opt=15); d.update(kw)
        return cell(**d)
    bad_tabs = [cell_table([c.add(cell_list(alpha, 1, [], count=0))]),             # count 0
                cell_table([c.add(cell_list(alpha, 1, [bcell()], count=513))]),     # count 513
                cell_table([c.add(cell_list(alpha, 1, [bcell(old=5)]))]),           # old 5
                cell_table([c.add(cell_list(alpha, 1, [bcell(new=31)]))]),          # new 31
                cell_table([c.add(cell_list(alpha, 1, [bcell(fill=16)]))]),         # fill 16
                cell_table([c.add(cell_list(alpha, 1, [bcell(opt=0x1F)]))]),        # opt bit 4
                cell_table([c.add(cell_list(c.add(alphabet([], n=0)), 1, [bcell()]))]),   # an alphabet of 0 codes
                cell_table([c.add(cell_list(c.add(alphabet([65, 31])), 1, [bcell()]))]),  # an alphabet code 31
                cell_table([cl0], n=0),                                             # a table of n 0
                cell_table([cl0], n=1025),                                          # n 1025
                cell_table([PAST]),                                                 # a list outside the cache
                cell_table([c.add(cell_list(alpha, 1, [bcell(font=PAST)]))]),       # a font outside the cache
                cell_table([c.add(cell_list(alpha, 1, [bcell()], res=1))]),         # the list header's reserved byte
                cell_table([cl0], res=1)]                                           # the table's reserved u16
    gpast = c.add(font224([PAST if k == 33 else gl[k % 6] for k in range(224)]))    # code 65's glyph record outside the cache
    bad_tabs.append(cell_table([c.add(cell_list(alpha, 1, [bcell(font=gpast, old=66, new=65)]))]))
    bad_c = [c.add(prog2(track(rect(40, 260, 200, 60), 1, gly(4, CELLS, c.add(t_))), bad=True)) for t_ in bad_tabs]
    bad_c.append(c.add(prog2(track(rect(40, 260, 200, 60), 1, gly(4, CELLS, ctab, 3)), bad=True)))   # index 3 of n 3: 5
    nN = c.add(prog2(track(rect(40, 260, 200, 60), 1, gly(5, CELLS, c.add(cell_table([c.add(cell_list(alpha, 5, [bcell()]))]))))))   # n − N = 0: 12
    g32past = c.add(font224([PAST] + [gl[k % 6] for k in range(1, 224)]))           # code 32's entry outside the cache (two-way: is 32 walked?)
    w32 = c.add(prog2(track(rect(40, 330, 200, 60), 1, gly(4, CELLS, c.add(cell_table([c.add(cell_list(alpha, 1, [bcell(font=g32past, old=32)]))]))))))
    st = [arm(c)]
    t = 2000
    for i, (p_, T_) in enumerate(pcl):
        st += play_to_end(i % 2, p_, 0x7600 + i, t, T_)
        t += 300
    gk = gl[(66 - 32) % 6]                                                           # the glyph code 66 names (cl0's first new code is 80; 66 is an alphabet code)
    st += [step(tk(t), msg(play(2, P, pcl[0][0], 0x7610))), step(tk(t + P), wake()),
           step(tk(t + P + 5), msg(cache_write2((gk, dict(c.entries)[gk])))),        # a cache write over a glyph record it reads: closed (outcome 1)
           step(tk(t + 2 * P), wake()),
           step(tk(t + 2 * P + 5), msg(play(2, P, pcl[0][0], 0x7611))),
           step(tk(t + 2 * P + 10), msg(cache_write2((alpha, dict(c.entries)[alpha])))),   # over its alphabet: closed
           step(tk(t + 2 * P + 20), msg(close(0xFF)))]
    t += 400
    st += [step(tk(t), msg(bind_list(0, NONE, 4, 0, 10, 1)),
                msg(bind_set(0, ANY, UP, 0, 1, 1, P, pk3, [(K_CUR, 0)])),            # index = the cursor before the move
                msg(bind_set(1, ANY, DOWN, 0, -1, 1, P, pk3, [(K_CUR, 2)])))]       # cursor + 2
    for k in range(3):                                                               # UP at cursors 0, 1, 2: lists 0, 1, 2
        st += [step(tk(t + 10 + 10 * k), inp(RING, UP)), step(tk(t + 10 + 10 * k), wake())]
    st += [step(tk(t + 50), inp(RING, DOWN)),                                       # cursor 3: index 5, past n 3: 5
           step(tk(t + 60), msg(bind_set(1, ANY, DOWN, 0, -1, 1, P, pk3, [(K_CUR, -1)])), inp(RING, DOWN)), step(tk(t + 60), wake()),   # index 2: drew
           step(tk(t + 60 + 3 * P), wake())]
    t += 400
    st += [step(tk(t + 10 * i), msg(play(3, P, b_, 0x7620 + i))) for i, b_ in enumerate(bad_c + [nN, w32])]
    vec("v5-cells", st)

    # ---------------------------------------------------------------------------------------------------------------
    # v5-rain — §7.6 "Kind 1 RAIN. src names a rain record (16 B) ... (levels 0..15; out of range or reserved bytes: 5).
    # With the track's area (l, top, Wa, Ha) on this lens, R = ⌈Ha / ch⌉. Column c lies at x_c = l + X(t) + c·cw, its cell
    # row r at y_r = top + Y(t) + r·ch, its start offset o_c = H2(seed, c) mod (spread + 1) and its head ... head_c = −o_c
    # + (((R + o_c + trail)·e) >> 12) ... Lit cells r with head_c − trail < r ≤ head_c and 0 ≤ r < R ...: the box filled
    # with fillLvl, then the glyph alphabet[H3(seed, c, r·256 + ⌊t / flicker⌋) mod n_alpha] drawn at (x_c, y_r), clipped
    # to its cell's box (code 32 draws nothing ...), through make_lut(⌊L_u·lvl(t) / 15⌋) with L_u = (headLvl·(trail − 1 −
    # u) + tailLvl·u) / (trail − 1) (headLvl when trail = 1). Above the trail ...: reveal 1 paints the column's cell boxes
    # from the shadow (`new`, OWN programs only), reveal 2 fills them with fillLvl, reveal 0 leaves them. Below the head
    # nothing is painted"; §7.2 "a rain track in place has dxL = dxR = dy = 0 (12)".
    c = Cache2(); fnt, gl = font(c, 571)
    al = c.add(alphabet(range(65, 75)))
    al32 = c.add(alphabet([32, 66, 32, 67]))
    rr = [c.add(rain_rec(14, 4, 8, 12, fnt, al, 15, 3, reveal=0, fill=1, spread=3, flicker=1)),     # reveal 0
          c.add(rain_rec(14, 4, 8, 12, fnt, al, 15, 2, reveal=1, fill=0, spread=5, flicker=2)),     # reveal 1
          c.add(rain_rec(14, 6, 8, 12, fnt, al, 14, 4, reveal=2, fill=5, spread=2, flicker=4)),     # reveal 2, flicker 4
          c.add(rain_rec(14, 1, 8, 12, fnt, al, 12, 3, reveal=0, fill=2, spread=0, flicker=1)),     # trail 1, spread 0
          c.add(rain_rec(24, 3, 5, 7, fnt, al, 15, 6, reveal=0, fill=3, spread=1, flicker=1)),      # glyphs up to 9 × 13 in 5 × 7 cells
          c.add(rain_rec(16, 3, 8, 12, fnt, al32, 15, 5, reveal=2, fill=0, spread=4, flicker=1))]   # code 32 skipped
    prn = [(c.add(prog2(track(rect(20, 20, 112, 50), 2, gly(8, RAIN, rr[0], seed=21)))), 8),                       # not OWN: END written; R = 5
           (c.add(prog2(track(rect(150, 20, 112, 50), BD_NONE, gly(8, RAIN, rr[1], x=2, seed=22)), fl=OWN | LAND)), 8),   # in place: the shadow above the trail
           (c.add(prog2(track(rect(280, 20, 112, 50), BD_NONE, gly(10, RAIN, rr[2], seed=23)), fl=OWN)), 10),
           (c.add(prog2(track(rect(410, 20, 112, 50), 4, gly(8, RAIN, rr[3], y=3, seed=24)))), 8),
           (c.add(prog2(track(rect(20, 100, 100, 70), 0, gly(8, RAIN, rr[4], seed=25, lvl=(15, 6))))), 8),   # 24 columns of 5 past the area's 100
           (c.add(prog2(track(rect(150, 100, 128, 60), BD_NONE, gly(8, RAIN, rr[5], seed=26)), fl=OWN)), 8),
           (c.add(prog2(track(rect(300, 100, 112, 50), 1, gly(8, RAIN, rr[0], dx=30, dy=8, seed=27)))), 8),   # moving offsets (not in place)
           (c.add(prog2(track((rect(420, 100, 112, 50), rect(436, 100, 112, 50)), 2, gly(8, RAIN, rr[0], seed=28, opt=0x80)))), 8)]   # per lens
    def brain(**kw):
        d = dict(cols=4, trail=3, cw=8, ch=12, font=fnt, alpha=al); d.update(kw)
        return c.add(rain_rec(**d))
    bad_r = [c.add(prog2(track(rect(20, 200, 112, 50), BD_NONE, gly(8, RAIN, rr[1])), bad=True)),               # reveal 1 outside OWN: 5
             c.add(prog2(track(rect(20, 200, 112, 50), BD_NONE, gly(8, RAIN, rr[2], dy=5)), fl=OWN)),           # in place, moving offsets: 12
             c.add(prog2(track((rect(20, 200, 112, 50), rect(36, 200, 112, 50)), BD_NONE, gly(8, RAIN, rr[2], xR=16, dxR=5)), fl=OWN))]   # per lens, dxR: 12
    bad_r += [c.add(prog2(track(rect(20, 200, 112, 50), 2, gly(8, RAIN, brain(**kw))), bad=True)) for kw in [
        dict(cols=0), dict(cols=129), dict(trail=0), dict(trail=33), dict(cw=0), dict(ch=0), dict(reveal=3), dict(head=16),
        dict(tail=16), dict(fill=16), dict(flicker=0), dict(res=1), dict(alpha=PAST), dict(font=PAST)]]
    st = [arm(c)]
    t = 2000
    for i, (p_, T_) in enumerate(prn):
        st += play_to_end(i % 2, p_, 0x7700 + i, t, T_)
        t += 400
    st += [step(tk(t + 10 * i), msg(play(2, P, b_, 0x7720 + i))) for i, b_ in enumerate(bad_r)]
    vec("v5-rain", st)

    # ---------------------------------------------------------------------------------------------------------------
    # v5-after — §7.7 "PLAY's close pass, except that an owning instance in slot `after` is not closed. If slot `after`
    # holds an owning instance, drawing or done, the new instance records that instance's (slot, tag, ordinal) and takes t0
    # = the later (signed 32-bit compare) of now and t0_a + T_a·P_a ... when it is drawing, and t0 = now when it is done;
    # otherwise t0 = now and nothing is recorded"; "When it is due: while its t0 is ahead of now ..., nothing is due and
    # its due time is t0; after t0, while its `after` slot holds the recorded (slot, tag, ordinal) in state drawing,
    # nothing is due and its due time is now plus its own P ... The copy hook decides each slot's waiting test on the slot
    # states as they stood when the present began, so an out half's step T and its in half's first step are never drawn
    # by one present, whatever their slots"; "At its first drawn step the hook empties the recorded instance — when the
    # `after` slot still holds that same triple and it was done when the present began — silently"; "A close of a waiting
    # instance ... is a close of a drawing instance: outcome 1"; §7.2 "for a PLAY-AFTER, the out half's last pose where the
    # out half owned".
    c = Cache2(); fnt, _ = font(c, 581)
    al = c.add(alphabet(range(65, 75)))
    R1r = c.add(rain_rec(30, 4, 8, 12, fnt, al, 15, 3, reveal=1, spread=4, flicker=1))
    OA = rect(100, 100, 240, 120)
    oh = c.add(prog2(track(OA, BD_NONE, pc(4, K_OLD, dx=-80, lvl=(15, 8))), fl=OWN))                   # the out half, T 4
    ih = c.add(prog2(track(OA, BD_NONE, r2(4, RIGHT, K_NEW, 100, 100)), fl=OWN | LAND))                 # the in half, T 4
    ihr = c.add(prog2(track(OA, BD_NONE, gly(5, RAIN, R1r, seed=31)), fl=OWN | LAND))                   # in place: rain reveal 1, T 5
    ih2 = c.add(prog2(track(rect(100, 100, 240, 200), BD_NONE, r2(4, TOP, K_NEW, 100, 100)), fl=OWN | LAND))   # wider than OA
    other = c.add(prog(track(rect(400, 300, 100, 40), 4, fillseg(4, 9))))
    near = c.add(prog(track(rect(120, 240, 60, 30), 5, fillseg(4, 10))))                                # meets ih2, not OA
    nonown = c.add(prog2(track(rect(400, 360, 100, 40), 5, f2(4, 8))))
    st = [arm(c)]
    st += [step(tk(2000), msg(play(2, P, oh, 0x8001))),                             # the out half: t0_a 2000, steps due 2000 .. 2102
           step(tk(2010), msg(batch(fill(rect(110, 110, 100, 40), 11), play_after(3, P, ih, 0x8002, 2)))),   # t0 = 2000 + 4·34 = 2136 (W: a future t0)
           step(tk(2034), wake()), step(tk(2068), wake()),
           step(tk(2102), wake()),                                                  # the out half's T: done, owning; the in half's t0 ahead
           step(tk(2136), wake()),                                                  # t0: the in half's step 1; the done out half emptied silently
           step(tk(2170), wake()), step(tk(2238), wake())]                          # its T: LAND
    st += [step(tk(3000), msg(play(3, P, oh, 0x8003))),                             # the slots the other way round: out in 3, in in 0
           step(tk(3010), msg(play_after(0, P, ih, 0x8004, 3))),                    # t0 = 3136
           step(tk(3140), wake()),                                                  # a late out half: its step T now; the in half waits (states as the present began)
           step(tk(3140), wake()),                                                  # the in half's step 1 in the next present
           step(tk(3140 + 3 * P), wake())]
    st += play_to_end(1, oh, 0x8005, 4000, 4) + [
           step(tk(4120), msg(play_after(2, P, ih, 0x8006, 1))),                    # a done owner: t0 = now, recorded; its own present draws step 1, empties slot 1
           step(tk(4120 + P), wake()), step(tk(4120 + 3 * P), wake())]
    st += [step(tk(5000), msg(play(1, P, oh, 0x8007))),
           step(tk(5010), msg(play_after(2, P, ih, 0x8008, 1))),                    # t0 = 5136
           step(tk(5034), wake()),
           step(tk(5050), msg(play(1, P, other, 0x8009))),                          # slot 1 played over: the out half closed (outcome 1); the in half's rect held (it owns)
           step(tk(5136), wake()),                                                  # t0: the in half draws; `other` (another triple) neither waited on nor emptied
           step(tk(5136 + 3 * P), wake())]
    st += [step(tk(5600), msg(play(0, P, oh, 0x800A))),
           step(tk(5610), msg(play_after(1, P, ih, 0x800B, 0))),
           step(tk(5620), msg(close(1))),                                           # a waiting instance: outcome 1
           step(tk(5600 + 3 * P), wake())]                                          # the out half to its T, untouched
    st += [step(tk(6000), msg(play(0, P, oh, 0x800C))),
           step(tk(6005), msg(batch(fill(rect(150, 120, 120, 60), 14), play_after(1, P, ihr, 0x800D, 0)))),   # the rain's base: the out half's last pose
           *[step(tk(6000 + k * P), wake()) for k in range(1, 9)]]
    st += [step(tk(6600), msg(close(0xFF)), tk(6600), msg(play(2, P, nonown, 0x800E))),
           step(tk(6610), msg(play_after(3, P, ih, 0x800F, 2))),                    # slot 2 not owning: t0 = now, nothing recorded; step 1 now
           step(tk(6610 + 3 * P), wake())]
    st += [step(tk(7000), msg(close(0xFF)), tk(7000), msg(play(0, P, oh, 0x8010))),
           step(tk(7010), msg(play(1, P, near, 0x8011)), tk(7010), msg(play(3, P, other, 0x8012))),
           step(tk(7020), msg(play_after(3, P, ih2, 0x8013, 0))),                   # closes its own slot's `other` and `near` (meets), not the owner in slot 0
           step(tk(7102), wake()), step(tk(7136), wake()), step(tk(7238), wake())]
    w0 = 0xFFFFFFA0                                                                 # across the clock's wrap (§7.7 "signed 32-bit compare")
    st += [step(tk(0x7FFFFFF0), {"lease": "acquire"}, flags(FULL5), msg(kf), *writes(c)),
           step(tk(0xFFFFFF80), {"lease": "acquire"}, flags(FULL5), msg(kf), *writes(c)),
           step(tk(w0), msg(play(0, P, oh, 0x8020))),                               # t0_a + 4·34 wraps to 0x28
           step(tk(w0 + 16), msg(play_after(1, P, ih, 0x8021, 0))),                 # the later by a signed compare: 0x28, not now
           *[step(tk((w0 + k * P) & M32), wake()) for k in (1, 2, 3, 4, 7)]]       # the out half's 2, 3, T; the in half's 1 and T after the wrap
    vec("v5-after", st)

    # ---------------------------------------------------------------------------------------------------------------
    # v5-ring — §7.8 "Mode 27 sub 4 LIST-RING ...: LIST's body, then flags u8 (bit 0 RING; bits 1–7 zero, 12). It applies
    # LIST and sets the list's RING flag to flags bit 0; a plain LIST clears it ... With RING the shift is applied modulo
    # the count: cursor := (old + shift) mod count, mod Euclidean ..., never lost by the shift; a lost cursor stays lost";
    # "A fire on a RING list: count 1 with a step ≠ 0 → the edge (a tap on a one-item list is not an edge); else c' =
    # (cursor + step) mod count (Euclidean) and the virtual cursor v = cursor + step (unwrapped). Parameters: kind 1 the
    # item table's entry at (v + arg) mod count (Euclidean; no item table → the edge); kind 2 v·pitch + arg (s32, outside
    # s16 → 12); kinds 0 and 3 as on any list ... The 115 carries field 18 = 1 when v ≠ c' ... EFFECTS clear at a fire on
    # a RING list → 15"; "SET's kind 3 (bit-11 build, while EFFECTS is armed; else 12): the parameter is cursor + arg, the
    # cursor before the move (s32, outside s16 → 12) ... A fire on an entry with a kind-3 parameter while EFFECTS is clear
    # ... → 15"; §6.3's order with LIST-RING 13 B and EFFECTS (15) after BIND.
    c, f = c2_fixture(); s = Seq()
    kp = c.add(prog2(track(A0, 2, d2(4, f.rows, 100, 0, lvl=(4, 15), pref0=pref(0, F_Y)))))       # y = P0
    NE = flags(DRAW2 | ENGINE | BIND | ZWRITE)
    def fire(t_, *ops): s.fire(); return [step(tk(t_), *ops), step(tk(t_), wake())]
    def miss(t_, *ops): s.fire(); return [step(tk(t_), *ops)]                     # a matched event that draws nothing
    ent = lambda e, ev, lst, slot, p4, prm: msg(bind_set(e, ANY, ev, lst, 1 if ev == UP else -1, slot, P, p4, prm))
    st = [arm(c), step(tk(1010), msg(bind_ring(0, f.itab, 4, 0, 10, 1)),
                       ent(0, UP, 0, 0, f.pan0, [(K_PITCH, 100)]), ent(1, DOWN, 0, 0, f.pan0, [(K_PITCH, 100)]))]   # y = v × 10 + 100
    st += fire(2000, inp(RING, DOWN))                                              # at 0: c' 3, v −1: y 90; field 18
    st += fire(2100, inp(RING, UP))                                                # at 3: c' 0, v 4: y 140; field 18
    st += fire(2200, inp(RING, UP))                                                # at 0: c' 1, no wrap: no field 18
    st += [step(tk(2300), ent(0, UP, 0, 0, f.itemp, [(K_ITEM, 0)]), ent(1, DOWN, 0, 0, f.itemp, [(K_ITEM, 2)]))]   # kind 1
    st += fire(2400, inp(RING, DOWN))                                              # at 1: c' 0, v 0: item 2
    st += fire(2500, inp(TOUCH_R, DOWN, 30))                                       # at 0: c' 3, v −1: item (−1 + 2) mod 4 = 1; field 18
    st += [step(tk(2600), ent(0, UP, 0, 0, f.itemp, [(K_ITEM, -7)]))]
    st += fire(2610, inp(RING, UP))                                                # at 3: c' 0, v 4: item (4 − 7) mod 4 = 1 (Euclidean)
    st += [step(tk(2700), msg(bind_ring(0, NONE, 4, 0, 10, 2)))] + miss(2710, inp(RING, UP))   # no item table: the edge
    st += [step(tk(2800), msg(bind_ring(0, f.itab, 4, 6, 10, 3))),                 # the shift modulo the count: 0 + 6 → 2
           step(tk(2810), msg(bind_ring(0, f.itab, 4, -9, 10, 4))),                # 2 − 9 → 1 (Euclidean)
           step(tk(2820), msg(bind_ring(0, f.itab, 4, 0, 10, 5, 2))),              # flags bit 1: 12, nothing applied
           step(tk(2830), msg(bind_ring(0, f.itab, 4, 0, 10, 5, 0)))]              # flags 0: RING := 0 (cursor 1)
    st += fire(2840, inp(RING, DOWN))                                              # c' 0: drew
    st += miss(2900, inp(RING, DOWN))                                              # c' −1 on a plain list: the edge
    st += [step(tk(2910), msg(bind_ring(0, f.itab, 4, 0, 10, 6)))]                 # RING again (cursor 0)
    st += fire(2920, inp(RING, DOWN))                                              # wraps: c' 3
    st += [step(tk(3000), msg(bind_list(0, f.itab, 4, 0, 10, 7)))] + miss(3010, inp(RING, UP))   # a plain LIST clears RING: at 3, the edge
    st += [step(tk(3020), msg(bind_list(0, f.itab, 4, 100, 10, 8))),               # lost by a plain shift
           step(tk(3030), msg(bind_ring(0, f.itab, 4, 1, 10, 9)))]                 # a lost cursor stays lost under LIST-RING
    st += miss(3040, inp(RING, UP))                                                # 18
    st += [step(tk(3050), msg(discard(0xFF, (s.n + 1 - 0x4000) & 0xFFFF, 0, 2)))]   # DISCARD restores it: 2
    st += fire(3060, inp(RING, UP))                                                # c' 3
    st += [step(tk(3100), msg(bind_clear(0)), msg(bind_clear(1)), msg(bind_ring(2, f.itab, 1, 0, 10, 1)),   # a one-item ring, slot 3
                ent(4, UP, 2, 3, f.press0, [(K_PITCH, 100)]), msg(bind_set(5, RING, TAP, 2, 0, 3, P, f.press0, [(K_PITCH, 100)])))]
    st += miss(3110, inp(TOUCH_L, UP, 20))                                         # count 1 with a step: the edge
    st += fire(3120, inp(RING, TAP))                                               # a tap on a one-item list is not an edge: drew
    st += [step(tk(3200), msg(bind_clear(4)), msg(bind_clear(5)), msg(bind_ring(3, NONE, 2, 0, 20000, 1)),
                ent(6, UP, 3, 3, f.press0, [(K_PITCH, 0)]))]                       # kind 2 at v: v × 20,000
    st += fire(3210, inp(RING, UP))                                                # at 0: c' 1, v 1: 20,000 drew
    st += miss(3220, inp(RING, UP))                                                # at 1: c' 0, v 2: 40,000 outside s16: 12 (c' × pitch fits)
    st += [step(tk(3300), msg(bind_clear(6)), msg(bind_ring(0, f.itab, 4, 0, 10, 10)),   # list 0 rings again at 3
                ent(0, UP, 0, 0, kp, [(K_CUR, 0)]), ent(1, DOWN, 0, 0, kp, [(K_CUR, 100)]))]   # kind 3: the cursor before the move
    st += fire(3310, inp(RING, UP))                                                # at 3: c' 0, y = 3
    st += fire(3320, inp(RING, DOWN))                                              # at 0: c' 3, y = 0 + 100
    st += [step(tk(3330), ent(0, UP, 0, 0, kp, [(K_CUR, 32765)]))] + miss(3340, inp(RING, UP))   # 3 + 32,765 = 32,768: 12
    st += [step(tk(3400), ent(1, DOWN, 0, 0, kp, [(K_PITCH, 100)]), msg(bind_list(1, f.itab, 4, 0, 10, 2)),
                ent(2, UP, 1, 2, kp, [(K_CUR, 0)]), msg(bind_clear(0)))]           # list 0 a ring with a kind-2 entry; list 1 plain with a kind-3 one
    st += [step(tk(3410), NE, tk(3410), msg(bind_ring(0, f.itab, 4, 0, 10, 11)),    # a hand disarm: LIST-RING 15
                msg(bind_set(3, ANY, UP, 1, 1, 2, P, kp, [(K_CUR, 1)])))]          # kind 3 at a SET with EFFECTS clear: 12
    st += miss(3420, inp(RING, DOWN))                                              # a fire on a RING list with EFFECTS clear: 15
    st += miss(3430, inp(RING, UP))                                                # a kind-3 entry on a plain list with EFFECTS clear: 15
    st += [step(tk(3440), flags(FULL5))]
    st += fire(3450, inp(RING, UP))                                                # armed again: list 1 at 0, y = 0
    st += [step(tk(3500), msg(bad(bind_ring(0, f.itab, 4, 0, 10, 12)[:-1]))),      # 12 B: 1
           step(tk(3510), msg(bad(bind_ring(0, f.itab, 4, 0, 10, 12) + b"\0"))),   # 14 B: 1
           step(tk(3520), msg(bind_ring(0, PAST, 4, 0, 10, 12))),                   # the item table outside the cache: 5
           step(tk(3530), msg(bind_ring(0, f.itab, 4, 0, 10, 13, 0x80))),          # flags bit 7: 12
           step(tk(3540), msg(commit(0, s.n, 0, 4, kp, (3,))))]                    # COMMIT's cursor 4 = count on a ring list: 12
    vec("v5-ring", st)

    # ---------------------------------------------------------------------------------------------------------------
    # v5-budget — §7.12 "The step budget (12, after the values ...). At a PLAY, a PLAY-AFTER, a RETARGET and a fire on a
    # bit-11 build the walk computes the opened or restarted instance's cost C and, for a version-2 program, judges the
    # sum ...; Every term is computed from both lenses' rects ... C is the sum over the program's tracks of 1,024 + the
    # backdrop's term + the largest of its segments' terms" (the term list), "a program with LAND adds its owned rect's
    # area (the larger lens's)"; "The cap is 8,000,000 u ... and it bounds the sum over every instance that can draw
    # after the message or fire: every slot of this lens in state drawing once the operation's closes are done (a waiting
    # PLAY-AFTER included; a done instance draws nothing ...) — except that an instance waiting on another ... and that
    # other count as the larger of the two ..., and a chain of such waits as its largest member. Over the cap: 12, nothing
    # opened or changed (a refused RETARGET leaves the instance as it was). Every term, each C and the sum are unsigned
    # 64-bit ... A 32-bit sum is wrong"; "Version-1 programs are not refused by the budget ..., but a version-1
    # instance's C is computed at its walk and counts in the sum". Each case: its tracks, then filler tracks (a level
    # backdrop over a WAIT or a record DRAW) that bring C, with whatever else draws, to exactly 8,000,000 — accepted — and
    # to 8,000,001 — refused 12 —, each sum asserted by this file's calculator (`cost`).
    c = Cache2()
    alt = lambda w, h: [(i + i // w) & 1 for i in range(w * h)]                    # one byte-token a pixel
    heavy = c.add(image2(640, 100, alt(640, 100)))                                 # L 64,000
    big = c.add(image2(200, 100, alt(200, 100)))                                   # L 20,000
    stripe = c.add(image2(60, 120, runs_pattern(60, 120, 591)))                    # long runs across rows: a window's edge tokens
    small = c.add(image2(20, 10, pattern(20, 10, 592)))
    gsol = c.add(glyph(40, 40, [9] * 1600))                                        # the largest w·h, one 4-byte token
    gnoi = c.add(glyph(20, 20, alt(20, 20)))                                       # a smaller glyph, the longest RLE (400 B)
    g32 = c.add(glyph(30, 30, alt(30, 30)))                                        # code 32's glyph (900 B)
    g6 = c.add(glyph(6, 10, pattern(6, 10, 593)))
    fb = c.add(font224([g32] + [g6] * 32 + [gsol, gnoi] + [g6] * 189))            # 32 → g32 · 65 → gsol · 66 → gnoi · else g6
    alB, alD, alN, alC = (c.add(alphabet(x)) for x in ([65, 66, 67], [67], [32], [32, 67]))
    clB = c.add(cell_list(alB, 1, [cell(16 * i, 40, 30, 30, fb, 67, 66, 2) for i in range(40)]))   # each: Lg 400 (66's), g 1,600 (65's)
    clW = c.add(cell_list(alD, 1, [cell(0, 0, 255, 255, fb, 67, 67, 3)] * 3))     # boxes larger than their area
    cl32 = c.add(cell_list(alD, 1, [cell(20 * i, 100, 16, 16, fb, 0, 32, 1) for i in range(30)]))   # codes 0 and 32 name no glyph
    clN = c.add(cell_list(alN, 1, [cell(10 * i, 300, 8, 8, fb, 0, 32, 4) for i in range(20)]))      # names none: its box and 64
    ctB = c.add(cell_table([clB, clW, cl32, clN]))
    rB = c.add(rain_rec(20, 6, 10, 14, fb, alB, 15, 4, fill=1, spread=3, flicker=2))
    rC = c.add(rain_rec(10, 4, 8, 12, fb, alC, 15, 4, fill=1, spread=2, flicker=1))     # its alphabet holds 32
    gA = c.add(glyph(255, 255, alt(255, 255)))                                     # A'1: 65,025 byte-tokens
    fA = c.add(font224([gA] * 224))
    alA = c.add(alphabet([65]))
    cellA = cell(0, 0, 255, 255, fA, 65, 65, 1)
    FULLR, TOPR = rect(0, 0, 640, 480), rect(0, 0, 640, 240)
    M = {}                                                                         # the calculator's memo: this cache's records do not move
    def exact(verb, fl=0, region=(0, 0, 640, 480), v1=False, Pv=(), others=0, count32=False):
        """The offsets of two programs: `verb`'s tracks and fillers inside `region` with C + others = CAP, and CAP + 1."""
        l0, t0, Wr, Hr = region
        R = lambda w, h: rect(l0, t0, w, h)
        if v1: segs = (drawrec(4, heavy, l0, t0), drawrec(4, big, l0, t0), fillseg(4, 6), wait(4))
        else: segs = (d2(4, heavy, l0, t0), d2(4, big, l0, t0), f2(4, 6), w2(4))
        mk = (lambda tr, reg: prog(*tr, reg=reg)) if v1 else (lambda tr, reg: prog2(*tr, fl=fl, reg=reg))
        opts = [track(R(Wr, hh), 2 if k < 3 else 1, segs[k]) for hh in {Hr, max(1, Hr // 2), max(1, Hr // 4)} for k in range(4)]
        unit = lambda tr: pcost(c, (prog(tr, reg=False) if v1 else prog2(tr, reg=False)), memo=M)
        opts = sorted(((unit(o), o) for o in opts), key=lambda x: -x[0])
        base = pcost(c, mk(verb, False), Pv, count32, memo=M) + others
        out = []
        for target in (CAP, CAP + 1):
            rest, pair_max = target - base, Wr * Hr + Wr + 2048
            hit = next(cb for n in range(0, 7 - len(verb)) for cb in combinations_with_replacement(opts, n)
                       if 2050 <= rest - sum(u for u, _ in cb) <= pair_max)     # the fewest fillers that leave the last two a remainder
            tr = list(verb) + [o for _, o in hit]
            r = rest - sum(u for u, _ in hit) - 2048
            h1 = next(h for h in range(Hr, 0, -1) if 1 <= r - min(Wr, (r - 1) // h) * h <= Wr and (r - 1) // h >= 1)
            w1 = min(Wr, (r - 1) // h1)
            tr += [track(R(w1, h1), 1, segs[3]), track(R(r - w1 * h1, 1), 1, segs[3])]
            assert len(tr) <= 8, f"{len(tr)} tracks"
            rec = mk(tr, True)
            got = pcost(c, rec, Pv, count32, memo=M) + others
            assert got == target, (target, got)
            out.append(c.add(rec))
        return out
    cases = [                                                                      # (what, the verb's tracks, exact's keywords)
        ("a record DRAW: 26·L + 4·A", [track(FULLR, 2, d2(4, big, 0, 0, dx=300))], {}),
        ("a record DRAW, its source window: L of the window's rows", [track(FULLR, 2, d2(4, stripe, 0, 0, aux0=37, aux1=20))], {}),
        ("a record DRAW whose width scales: 26·L + 10·A + w·Ha", [track(FULLR, 2, d2(4, big, 0, 0, w0=300, w1=600))], {}),
        ("a record REVEAL", [track(FULLR, 2, r2(4, TOP, K_RECORD, 0, 0, src=big))], {}),
        ("DRAW `new`: 3·A", [track(FULLR, 2, dnew(4, 0, 0, dx=40))], dict(fl=OWN)),
        ("DRAW `new` whose width scales: 10·A", [track(FULLR, 2, dnew(4, 0, 0, w0=320, w1=640))], dict(fl=OWN)),
        ("REVEAL of a level, of `new`: A", [track(FULLR, 2, r2(4, TOP, K_LEVEL, 0, 0, src=7, w=300, h=200)),
                                            track(FULLR, 3, r2(4, LEFT, K_NEW, 0, 0))], dict(fl=OWN)),
        ("FILL: A; the backdrop `new`: A", [track(FULLR, 2, f2(4, 6)), track(FULLR, BD_NEW, w2(4))], dict(fl=OWN)),
        ("PIECES layer new: 3·A", [track(FULLR, 2, pc(4, K_NEW, grid=(8, 8), model=RANDOM, dx=40, dy=40))] * 2, dict(fl=OWN)),
        ("PIECES layer old: 3·A", [track(TOPR, BD_NONE, pc(4, K_OLD, dx=40, vacate=1))], dict(fl=OWN, region=(0, 240, 640, 240))),
        ("SHRINK: 3·A", [track(TOPR, BD_NONE, sh(4, h=(240, 60)))], dict(fl=OWN, region=(0, 240, 640, 240))),
        ("SHRINK whose width scales: 13·A", [track(TOPR, BD_NONE, sh(4, w=(640, 100), h=(240, 60)))], dict(fl=OWN, region=(0, 240, 640, 240))),
        ("CELLS: Lg and g each its own largest; a box over its area", [track(FULLR, 2, gly(4, CELLS, ctB, 0)),
                                                                       track(rect(0, 0, 100, 80), 2, gly(4, CELLS, ctB, 1))], {}),
        ("CELLS: codes 0 and 32 not counted", [track(FULLR, 2, gly(4, CELLS, ctB, 2))], {}),
        ("CELLS: a cell naming none counts its box and 64", [track(FULLR, 2, gly(4, CELLS, ctB, 3))], {}),
        ("RAIN: A + cols·trail·(11·cw·ch + 16·Lg + 64)", [track(FULLR, 2, gly(4, RAIN, rB))], {}),
        ("RAIN, code 32 in the alphabet, read as naming no glyph (two-way, §7.12)", [track(FULLR, 2, gly(4, RAIN, rC))], dict(count32=False)),
        ("RAIN, code 32 in the alphabet, read as naming its glyph", [track(FULLR, 2, gly(4, RAIN, rC))], dict(count32=True)),
        ("LAND: the owned rect of the larger lens", [track(rect(0, 0, 340, 480), 2, dnew(4, 0, 0, w0=200, w1=340)),
                                                     track((rect(340, 0, 100, 480), rect(440, 0, 100, 480)), 2, f2(4, 5))],
         dict(fl=OWN | LAND, region=(0, 0, 340, 480))),
        ("a version-1 program counted alone (never refused)", [track(TOPR, 2, drawrec(4, big, 0, 0))], dict(v1=True)),
    ]
    built = [(what, *exact(tr, **kw)) for what, tr, kw in cases]
    selfp = exact([track(rect(0, 0, 200, 100), BD_SELF, d2(4, 0, 0, 0, kind=K_SELF, dx=20)),
                   track(rect(0, 100, 200, 100), 3, r2(4, LEFT, K_SELF, 0, 100))])    # `self`: 10·A (backdrop, DRAW, REVEAL)
    rtsel = [track(FULLR, 2, d2(4, 0, 0, 0, pref0=pref(0, F_SRC)))]                 # src = P0: RETARGET re-walks it
    rt_at, rt_over = exact(rtsel, Pv=(heavy,))
    X = c.add(prog2(track(TOPR, 2, d2(4, heavy, 0, 0))))                            # another instance, drawing in slot 0
    CX = cost(c.buf, X, memo=M)
    y_at, y_over = exact([track(rect(0, 240, 640, 240), 3, f2(4, 9))], region=(0, 240, 640, 240), others=CX)
    Z = c.add(prog2(track(rect(0, 0, 640, 100), 2, d2(4, heavy, 0, 0)), track(rect(0, 0, 640, 100), 2, d2(4, big, 0, 0))))
    CZ = cost(c.buf, Z, memo=M)
    R1, R2, RI = rect(0, 200, 300, 280), rect(340, 200, 300, 280), rect(0, 200, 640, 280)
    O = c.add(prog2(track(R1, 2, dnew(4, 0, 200, w0=150, w1=300)), fl=OWN))         # the out half (slot 2)
    i_at, i_over = exact([track(RI, BD_NONE, r2(4, TOP, K_NEW, 0, 200))], fl=OWN, region=(0, 200, 640, 280), others=CZ)
    j_at, j_over = exact([track(R2, 2, w2(4))], fl=OWN, region=(340, 200, 300, 280), others=CZ)   # meets I's area, not O's
    assert cost(c.buf, O, memo=M) < cost(c.buf, i_at, memo=M) == cost(c.buf, j_at, memo=M)   # the chain's largest: CAP − C_Z
    v1big = c.add(prog(*[track(TOPR, 2, drawrec(4, heavy, 0, 0))] * 4))             # C over the cap: a version-1 PLAY is never refused
    assert cost(c.buf, v1big, memo=M) > CAP
    v1s = c.add(prog(track(TOPR, 2, drawrec(4, big, 0, 0))))
    v2s_at, v2s_over = exact([track(rect(0, 240, 640, 240), 3, f2(4, 9))], region=(0, 240, 640, 240), others=cost(c.buf, v1s, memo=M))
    one = c.add(prog2(track(rect(0, 300, 100, 40), 3, f2(4, 9))))                   # a small version-2 program apart from TOPR
    bf_at, bf_over = exact([track(FULLR, 2, d2(4, big, 0, 0, pref0=pref(0, F_Y)))])  # bound-shaped: at a fire and a COMMIT (P0 = 0)
    lA = c.add(cell_list(alA, 1, [cellA] * 512))
    def wrap_parts(cc, m):                                                         # four tracks of 512 such cells and one of m
        lm = cc.add(cell_list(alA, 1, [cellA] * m)); ct = cc.add(cell_table([lA, lm]))
        return [track(FULLR, 0, gly(4, CELLS, ct, 0))] * 4 + [track(FULLR, 0, gly(4, CELLS, ct, 1))]
    def wrap_trial(m):
        tmp = Cache2(); tmp.buf = bytearray(c.buf)
        return pcost(tmp, prog2(*wrap_parts(tmp, m), reg=False), memo=M)
    lo_, hi_ = 1, 512                                                              # the fewest cells whose sum reaches 2^32
    assert wrap_trial(lo_) < 1 << 32 <= wrap_trial(hi_)
    while hi_ - lo_ > 1:
        mid_ = (lo_ + hi_) // 2
        lo_, hi_ = (mid_, hi_) if wrap_trial(mid_) < 1 << 32 else (lo_, mid_)
    wrap = c.add(prog2(*wrap_parts(c, hi_)))
    SA = cost(c.buf, wrap, memo=M)
    assert (1 << 32) <= SA < (1 << 32) + CAP and SA % (1 << 32) < CAP, SA          # A'1: the 64-bit sum refuses; a 32-bit one wraps under the cap
    scap = c.reserve(cap_total((200, 100), (200, 100)))                             # after every program: outside each first range
    st = [arm(c, size=256)]
    t = 2000
    for i, (what, at, over) in enumerate(built + [("`self`", *selfp)]):
        cap4 = scap if what == "`self`" else 0
        st += [step(tk(t), msg(play(0, P, at, 0x7800 + 2 * i, cap4))),             # `what` at exactly 8,000,000: taken, step 1
               step(tk(t + 10), msg(play(0, P, over, 0x7801 + 2 * i, cap4))),      # one unit over: 12, the first draws on (version 1: taken)
               step(tk(t + 20), msg(close(0xFF)))]
        t += 100
    st += [step(tk(t), msg(play(0, P, rt_over, 0x7840, P=(small,)))), step(tk(t + P), wake()),    # under the cap with the small record
           step(tk(t + P + 5), msg(retarget(0, (heavy,)))),                         # the heavy record: one over: 12, unchanged
           step(tk(t + 2 * P), wake()),                                             # its own step 3
           step(tk(t + 2 * P + 5), msg(close(0)), tk(t + 2 * P + 5), msg(play(0, P, rt_at, 0x7841, P=(small,)))),
           step(tk(t + 2 * P + 10), msg(retarget(0, (heavy,)))),                    # exactly the cap: restarted
           step(tk(t + 3 * P + 10), wake()), step(tk(t + 3 * P + 20), msg(close(0xFF)))]
    t += 400
    st += [step(tk(t), msg(play(0, P, X, 0x7850))),                                 # X draws (slot 0)
           step(tk(t + 5), msg(play(1, P, y_at, 0x7851))),                          # X + Y = 8,000,000: taken
           step(tk(t + 10), msg(play(1, P, y_over, 0x7852))),                       # a second instance one over: 12 (slot 1's own closed first)
           step(tk(t + 3 * P), wake()),                                             # X's last step: done
           step(tk(t + 3 * P + 10), msg(play(1, P, y_over, 0x7853))),               # a done one is not counted: taken
           step(tk(t + 3 * P + 20), msg(close(0xFF)))]
    t += 400
    st += [step(tk(t), msg(play(0, P, Z, 0x7860))),
           step(tk(t + 5), msg(play(2, P, O, 0x7861))),                             # the out half
           step(tk(t + 10), msg(play_after(3, P, i_over, 0x7862, 2))),              # waiting on O: Z + max(O, I) one over: 12
           step(tk(t + 15), msg(play_after(3, P, i_at, 0x7863, 2))),                # exactly the cap (the sum of all three is not): taken
           step(tk(t + 20), msg(play_after(1, P, j_over, 0x7864, 3))),              # a chain O ← I ← J as its largest: one over: 12
           step(tk(t + 25), msg(play_after(1, P, j_at, 0x7865, 3))),                # taken
           step(tk(t + 30), msg(close(0xFF)))]
    t += 400
    st += [step(tk(t), msg(play(0, P, v1big, 0x7870))),                             # a version-1 program over the cap: taken
           step(tk(t + 10), msg(play(1, P, one, 0x7871))),                          # it counts: the sum over: 12
           step(tk(t + 20), msg(close(0)), tk(t + 20), msg(play(0, P, v1s, 0x7872))),
           step(tk(t + 30), msg(play(1, P, v2s_at, 0x7873))),                       # version 1 + version 2 = 8,000,000: taken
           step(tk(t + 40), msg(play(1, P, v2s_over, 0x7874))),                     # one over: 12
           step(tk(t + 50), msg(close(0xFF))),
           step(tk(t + 60), msg(play(0, P, wrap, 0x7875)))]                         # A'1's cells: 64-bit, over: 12
    t += 200                                                                        # §7.12 "at a PLAY, a PLAY-AFTER, a RETARGET and a fire";
    st += [step(tk(t), page(True), msg(bind_list(0, NONE, 8, 0, 10, 1)),           # "a COMMIT ... judges its program's own C against the cap"
                msg(bind_set(0, ANY, UP, 0, 1, 0, P, bf_over, [(K_ARG, 0)])), inp(RING, UP)),   # a fire one over: 12
           step(tk(t + 10), msg(bind_set(0, ANY, UP, 0, 1, 0, P, bf_at, [(K_ARG, 0)])), inp(RING, UP)),   # exactly the cap: drew
           step(tk(t + 10), wake()),
           step(tk(t + 20), msg(commit(1, 0x7777, 0, 0, bf_over, (0,)))),          # a COMMIT one over: 12
           step(tk(t + 30), msg(commit(1, 0x7777, 0, 0, bf_at, (0,))))]            # exactly the cap: taken
    vec("v5-budget", st)

    for vec_ in v:
        vec_["contract"] = 2
        vec_["start"] = "zero"
        vec_["panel"] = True
        vec_["features"] = [FEATURES_C3]
    return v

def vectors_v5_x2(kf):
    """Stage X2D's C3 inputs (`C3-decisions.md` W and Y; the X2D brief's item 8 and C-6): NEW files `v5-x2-*` only — the
    86 earlier files stay byte-identical. Each case is shaped so that the rule absent, or read the other way, answers
    differently; each step's comment names the §7 sentence and the other answer. The forms are `vectors_v5`'s: features
    [0xdff], "panel", contract 2, every engine message, input and wake after a `tick` op in its step."""
    v = []
    P = 34                                                        # §5.3's starting period
    def writes(c): return [msg(m) for m in c.messages()]
    def vec(name, steps): v.append({"name": name, "steps": steps})
    def arm(c, t=1000, fl=FULL5):
        assert len(c.buf) < 60 * 1024, f"{len(c.buf)} B: past a 64 KiB session's cache"
        return step(tk(t), {"lease": "acquire"}, flags(fl), page(True), msg(kf), *writes(c))
    def gp(c, *tr, **kw): return c.add(prog2(*tr, **kw))
    def bp(c, *tr, **kw): return c.add(prog2(*tr, bad=True, **kw))
    def owner(c): return c.add(prog2(track(rect(600, 440, 20, 20), 5, w2(8)), fl=OWN))   # an out half: 1,124 u, T 8, apart
    def tune(c, region, target, tracks=(), fl=OWN):
        """An OWN program of `tracks` and fillers inside `region` (l, t, w, h) whose §7.12 C is exactly `target`, T 8:
        n tracks of the region and one of w × q0, each backdrop `new` under a two-axis DRAW of `new` (1,024 + 11·A), then
        two level backdrops over a WAIT (1,024 + A) of w × q2 and k2 × 1 (EffectsSimTest's `heavy`, the same form)."""
        l, t0, w, h = region
        base = pcost(c, prog2(*tracks, fl=fl, reg=False)) if tracks else 0
        A = w * h
        unit = 1024 + 11 * A
        def big(hh): return track(rect(l, t0, w, hh), BD_NEW, dnew(8, l, t0, w0=1, w1=1))
        def lvl(ww, hh): return track(rect(l, t0, ww, hh), 2, w2(8))
        rest = target - base
        n = (rest - 3 * 1024 - 12 * w - 1) // unit
        assert 0 <= n <= 5 - len(tracks), (target, n)
        m = rest - n * unit - 3 * 1024
        q0 = min(h, (m - w - 1) // (11 * w))
        rem = m - 11 * w * q0
        q2, k2 = (rem - 1) // w, rem - (rem - 1) // w * w
        assert 1 <= q0 <= h and 1 <= q2 <= h and 1 <= k2 <= w, (q0, q2, k2)
        rec = prog2(*tracks, *([big(h)] * n), big(q0), lvl(w, q2), lvl(k2, 1), fl=fl)
        assert pcost(c, rec) == target, (pcost(c, rec), target)
        return c.add(rec)

    # ---------------------------------------------------------------------------------------------------------------
    # v5-x2-budget — §7.12 "The cap ... bounds the sum over every instance that can draw after the message or fire: every
    # slot of this lens in state drawing once the operation's closes are done ... an instance *waits on* another when it is
    # a PLAY-AFTER whose `after` slot still holds that other's recorded triple. Each instance counts f(i) = max(C_i, Σ f(w)
    # over the instances w that wait on i), and the sum runs over the instances that wait on none ... instances waiting on
    # one instance draw together once it is done, so they add. (Should the records form a cycle — only a reused tag can
    # ... — it is cut before f is taken: for i = 0, 1, 2, 3 in order, if following *waits on* from slot i, with the cuts
    # made so far, comes back to i, slot i's own wait is dropped ...)" (ruled from both sides' answers, 2026-11-20); §7.3 "an OWN program being opened meets by its own meeting box in its
    # PLAY's or PLAY-AFTER's close pass"; §6.2 step 10's budget before step 11's 19; the terms "DRAW or REVEAL of a record:
    # 26·L + 4·A" (a DRAW scaled in H only stays there) and "GLYPHS RAIN: A + cols·trail·(cw·ch + 64 + G), with G = ... 0"
    # for an alphabet of only code 32. Every program here is T 8, so each stays drawing through its group's messages.
    c = Cache2()
    O = owner(c)
    X = c.add(prog(track(rect(290, 100, 60, 200), 2, fillseg(8, 4))))  # version 1, in G's gap: 1,024 + 2·12,000 = 25,024
    assert cost(c.buf, X) == 25_024
    right = track(rect(360, 0, 280, 480), BD_NEW, dnew(8, 360, 0, w0=1, w1=1))   # G's right side; its left side the fillers
    G_at, G_over = (tune(c, (0, 0, 280, 480), CAP + k, tracks=[right]) for k in (0, 1))   # a gap [280, 360) inside G's box
    W4 = tune(c, (0, 0, 320, 480), 4_000_000)                           # an in half over the left half
    X4, X4o = (tune(c, (320, 0, 320, 400), 4_000_000 + k) for k in (0, 1))   # an OWN PLAY apart from W4
    A4 = tune(c, (0, 0, 320, 240), 4_000_000)
    Z4 = tune(c, (320, 0, 320, 400), 4_000_000)                         # apart from A4, N4 and the owner
    N4, N4o = (tune(c, (0, 240, 320, 240), 4_000_000 + k) for k in (0, 1))
    A2 = tune(c, (0, 0, 320, 240), 2_000_000)
    H4 = tune(c, (320, 0, 320, 400), 4_000_000)
    N2, N2o = (tune(c, (0, 240, 320, 240), 2_000_000 + k) for k in (0, 1))
    Lb = c.add(prog2(track(rect(0, 300, 100, 50), 2, f2(3, 9))))       # a fire's bound program: 1,024 + 2·5,000 = 11,024
    CL = cost(c.buf, Lb)
    assert CL == 11_024
    Z19, Z12 = (tune(c, (320, 0, 320, 240), CAP + k - 4_000_000 - CL) for k in (0, 1))
    Z4b = tune(c, (0, 240, 600, 240), 4_000_000)                        # beside the owner (its x 600 is outside)
    W2b, W2bo = (tune(c, (320, 0, 320, 240), 2_000_000 + k) for k in (0, 1))
    img = c.add(image2(40, 30, pattern(40, 30, 7101)))
    hdraw = [track(rect(0, 0, 640, 240), 2, d2(8, img, 10, 10, h0=30, h1=60))]          # H scaled, the width not
    H_at, H_over = (tune(c, (0, 240, 640, 240), CAP + k, tracks=hdraw) for k in (0, 1))
    g6 = c.add(glyph(6, 10, pattern(6, 10, 7102)))
    f32 = c.add(font224([g6] * 224))
    al32 = c.add(alphabet([32]))
    r32 = c.add(rain_rec(10, 4, 8, 12, f32, al32, fill=2))
    rain32 = [track(rect(0, 0, 640, 240), 2, gly(8, RAIN, r32))]       # 153,600 + 40·(96 + 64) = 160,000 by the term
    R_at, R_over = (tune(c, (0, 240, 640, 240), CAP + k, tracks=rain32) for k in (0, 1))
    st = [arm(c)]
    t = 2000
    st += [step(tk(t), msg(play(0, P, X, 0x7A00))),                         # X draws in what will be G's gap
           step(tk(t + 5), msg(play(1, P, G_at, 0x7A01))),                 # G meets X by its own box: X closed (its body), G alone
                                                                           # at the cap: taken. Budget judged before that close: 12
           step(tk(t + 10), msg(close(0xFF))),
           step(tk(t + 20), msg(play(0, P, X, 0x7A02))),
           step(tk(t + 25), msg(play(1, P, G_over, 0x7A03))),              # G alone one over: 12, X left open
           step(tk(t + 30), msg(close(0xFF)))]
    t += 300
    for i, (x_, tag) in enumerate(((X4, 0x7A13), (X4o, 0x7A23), (X4o, 0x7A31))):
        b = 0x7A11 + 16 * i
        st += [step(tk(t), msg(play(0, P, O, b))),                          # O, an out half, in slot 0
               step(tk(t + 5), msg(play_after(1, P, W4, b + 1, 0))),       # W waits on O: records (0, O's tag, 0)
               step(tk(t + 10), msg(play(0, P, x_, tag))),                 # X replaces O: W waits on none and counts its own C —
                                                                           # 4,000,000 + X: X at 4,000,000 taken, one over 12; with O's
                                                                           # tag reused W waits on X (the larger): taken
               step(tk(t + 15), msg(close(0xFF)))]
        t += 100
    t += 200
    for i, n_ in enumerate((N4, N4o)):
        b = 0x7A41 + 16 * i
        st += [step(tk(t), msg(play(0, P, O, b))),                          # B in slot 0
               step(tk(t + 5), msg(play_after(1, P, A4, b + 1, 0))),       # A waits on B
               step(tk(t + 6), msg(play(2, P, Z4, b + 2))),                # f(B) = 4,000,000 with Z's: exactly the cap, taken
               step(tk(t + 10), msg(play_after(0, P, n_, b + 3, 1))),      # N replaces B: A waits on none, N waits on A — f(A) +
                                                                           # Z: N 4,000,000 taken, one over 12 (with A read as still
                                                                           # waiting on slot 0, A and N count nothing: taken)
               step(tk(t + 15), msg(close(0xFF)))]
        t += 100
    for i, n_ in enumerate((N4, N4o)):
        b = 0x7A61 + 16 * i
        st += [step(tk(t), msg(play(0, P, O, b))),
               step(tk(t + 5), msg(play_after(1, P, A4, b + 1, 0))),
               step(tk(t + 10), msg(play_after(0, P, n_, b, 1))),          # N with B's tag: slot 0 holds A's triple again, A waits
                                                                           # on N and N on A — a cycle, cut at slot 0 (N): the larger,
                                                                           # taken at the cap and one over (each member by its own
                                                                           # C, 4,000,000 + N: one over 12)
               step(tk(t + 15), msg(close(0xFF)))]
        t += 100
    for i, n_ in enumerate((N2, N2o)):
        b = 0x7A81 + 16 * i
        st += [step(tk(t), msg(play(0, P, O, b))),
               step(tk(t + 5), msg(play_after(1, P, A2, b + 1, 0))),       # A (2,000,000) waits on B
               step(tk(t + 6), msg(play_after(2, P, H4, b + 2, 1))),       # H (4,000,000) waits on A: f(B) = 4,000,000, taken
               step(tk(t + 10), msg(play_after(0, P, n_, b, 1))),          # the cycle A–N with H waiting on A (H neither a root nor a
                                                                           # member): cut at slot 0 (N), A waits on N, H on A — one
                                                                           # group by f, 4,000,000: N 2,000,000 and one over taken
                                                                           # (H and the members each by own C, 2,000,000 + H + N:
                                                                           # one over 12)
               step(tk(t + 15), msg(close(0xFF)))]
        t += 100
    st += [step(tk(t), page(True), msg(bind_list(0, NONE, 8, 0, 10, 1)),
                msg(bind_set(0, ANY, UP, 0, 1, 0, P, Lb)))]                 # an entry on slot 0, its program Lb
    for i, z_ in enumerate((Z19, Z12)):
        b = 0x7AA1 + 16 * i
        st += [step(tk(t + 5), msg(play(0, P, O, b))),                      # an owner in the entry's slot
               step(tk(t + 7), msg(play_after(1, P, A4, b + 1, 0))),       # W (4,000,000) waits on it
               step(tk(t + 9), msg(play(2, P, z_, b + 2))),                # Z apart from W and Lb
               step(tk(t + 11), inp(RING, UP)),                            # the close set closes the owner, so W waits on none:
                                                                           # W + Z + 11,024 at the cap passes the budget and step 11
                                                                           # refuses 19; one over: 12 (W folded under the local: 19)
               step(tk(t + 15), msg(close(0xFF)))]
        t += 100
    for i, w_ in enumerate((W2b, W2bo)):
        b = 0x7AC1 + 16 * i
        st += [step(tk(t), msg(play(3, P, Z4b, b))),                        # Z (4,000,000) beside them
               step(tk(t + 3), msg(play(0, P, O, b + 1))),                 # the out half
               step(tk(t + 6), msg(play_after(1, P, A2, b + 2, 0))),       # two waiters on it: 2,000,000 ...
               step(tk(t + 9), msg(play_after(2, P, w_, b + 3, 0))),       # ... and 2,000,000 add: with Z exactly the cap, taken; the
                                                                           # second one over: 12 (as the larger: taken)
               step(tk(t + 15), msg(close(0xFF)))]
        t += 100
    for i, (at, over) in enumerate(((H_at, H_over), (R_at, R_over))):
        b = 0x7AE1 + 16 * i
        st += [step(tk(t), msg(play(0, P, at, b))),                         # at the cap with 26·L + 4·A for the H-scaled DRAW, or
                                                                           # G = 0 for the rain of only code 32: taken (10·A + w·Ha,
                                                                           # or a G counted: 12)
               step(tk(t + 5), msg(close(0xFF))),
               step(tk(t + 10), msg(play(0, P, over, b + 1))),             # one over: 12
               step(tk(t + 15), msg(close(0xFF)))]
        t += 100
    vec("v5-x2-budget", st)

    # ---------------------------------------------------------------------------------------------------------------
    # v5-x2-meet — §7.3 "Every meeting decision uses the box — a PLAY's and a PLAY-AFTER's close pass, a COMMIT's close
    # pass, close-on-meet's area test, and the fire's close set"; "two owning instances meet when their meeting boxes
    # intersect" — here two per-lens owners whose own rects meet on neither lens.
    c = Cache2()
    OAg = gp(c, track(rect(100, 80, 60, 40), 3, w2(8)), track(rect(300, 80, 60, 40), 4, w2(8)), fl=OWN)   # box [100, 360) × [80, 120)
    band = c.add(prog(track(rect(200, 90, 40, 20), 6, fillseg(3, 9))))      # in OAg's gap: meets its box, neither track
    P1 = gp(c, track((rect(100, 300, 40, 30), rect(180, 300, 40, 30)), 3, w2(8)), fl=OWN)   # box [100, 220)
    P2 = gp(c, track((rect(190, 300, 40, 30), rect(230, 300, 40, 30)), 4, w2(8)), fl=OWN)   # box [190, 270); apart on each lens
    st = [arm(c)]
    t = 2000
    st += [step(tk(t), page(True), msg(bind_list(0, NONE, 8, 0, 10, 1))),
           step(tk(t + 5), msg(play(0, P, OAg, 0x7B01))),
           step(tk(t + 10), msg(commit(3, 1, 0, 0, band))),                # the COMMIT's close pass meets OAg by its box: closed (its
                                                                           # outcome-1 body); by its tracks: left open
           step(tk(t + 10 + P), wake()),
           step(tk(t + 20 + P), msg(close(0xFF))),
           step(tk(t + 100), msg(play(0, P, P1, 0x7B02))),
           step(tk(t + 105), msg(play(1, P, P2, 0x7B03))),                 # the boxes meet: P1 closed on both lenses (by each lens's
                                                                           # own rects: P1 left open)
           step(tk(t + 105 + P), wake()),
           step(tk(t + 115 + P), msg(close(0xFF)))]
    vec("v5-x2-meet", st)

    # ---------------------------------------------------------------------------------------------------------------
    # v5-x2-stagger — §7.4 "Layer new ... through the level ramp `make_lut(lvl(t))` with lvl(t) eased from lvl0 to lvl1 as
    # DRAW's — at the segment's local step t over its steps; under stagger, ease 2 (whose table holds n' entries) reads E
    # at min(t, n'), E[n'] = 4096 standing for every later step". A level ramp 2 → 15 under stagger 3 of 8, at ease 0, 1
    # and 2, a wake at every step: the panel shows each step's level (read over n' = 5 for ease 0 and 1: other panels).
    c = Cache2()
    stab = c.add(easing([600, 1500, 2600, 3500, 4096]))                      # n 5 = 8 − 3
    sg = [gp(c, track(rect(40 + 300 * (k % 2), 40 + 220 * (k // 2), 240, 160), 2,
                      pc(8, K_NEW, grid=(4, 3), model=RANDOM, stagger=3, dx=-60, dy=30, f=(4096, 0), lvl=(2, 15), seed=21 + k, **kw)), fl=OWN)
          for k, kw in enumerate(({}, dict(ease=EASE_OUT), dict(ease=TABLE, table=stab)))]
    st = [arm(c)]
    t = 2000
    for i, p_ in enumerate(sg):
        st += [step(tk(t), msg(play(i, P, p_, 0x7C01 + i)))] + [step(tk(t + k * P), wake()) for k in range(1, 8)]
        st += [step(tk(t + 7 * P + 10), msg(close(0xFF)))]
        t += 400
    vec("v5-x2-stagger", st)

    # ---------------------------------------------------------------------------------------------------------------
    # v5-x2-effects — §7.9 "Nothing of C3 is taken with flag bit 6 clear: a version-2 record is 5 wherever it is walked (a
    # PLAY, RETARGET, fire, COMMIT or SET)"; "A disarm closes nothing". The RETARGET, the fire and the COMMIT with EFFECTS
    # clear (each taken were EFFECTS not read there), then the same three after EFFECTS is armed again: taken.
    c = Cache2()
    pic = c.add(image2(40, 30, pattern(40, 30, 7201)))
    Rv = gp(c, track(rect(0, 400, 300, 40), 2, d2(4, pic, 10, 405, dx=200)))           # version 2, retargetable
    Fv = gp(c, track(rect(0, 300, 300, 40), 3, d2(3, pic, 10, 305, dx=100)))           # version 2, an entry's
    Cv = gp(c, track(rect(320, 300, 300, 40), 4, f2(3, 9)))                            # version 2, a COMMIT's
    NE = DRAW2 | ENGINE | BIND | ZWRITE                                                 # 0x3c: EFFECTS clear
    st = [arm(c)]
    t = 2000
    st += [step(tk(t), page(True), msg(bind_list(0, NONE, 8, 0, 10, 1)), msg(bind_set(0, ANY, UP, 0, 1, 1, P, Fv))),
           step(tk(t + 5), msg(play(0, P, Rv, 0x7D01))),
           step(flags(NE)),                                                # EFFECTS cleared: the instance draws on
           step(tk(t + 10), msg(retarget(0))),                             # its walk: version 2 without EFFECTS, 5 (taken: unread)
           step(tk(t + 15), inp(RING, UP)),                                # the fire's walk: 5
           step(tk(t + 20), msg(commit(2, 0x7D10, 0, 0, Cv))),             # the COMMIT's walk: 5
           step(tk(t + 25), wake()),
           step(flags(FULL5)),
           step(tk(t + 30), msg(retarget(0))),                             # EFFECTS armed again: restarted
           step(tk(t + 35), inp(RING, UP)),                                # drew
           step(tk(t + 40), msg(commit(2, 0x7D11, 0, 0, Cv))),             # taken
           step(tk(t + 40 + P), wake()),
           step(tk(t + 50 + P), msg(close(0xFF)))]
    vec("v5-x2-effects", st)

    # ---------------------------------------------------------------------------------------------------------------
    # v5-x2-order — §7.2 rules no earlier vector answers differently without: "Every eased level of DRAW, PIECES, SHRINK
    # and GLYPHS is 0..15 ... (12)" (SHRINK's); "On a track that is not per-lens no rule reads xR or dxR: for every verb of a
    # version-2 record they are ignored" (each verb with an R side, the radial PIECES among them: per-lens readings draw
    # other pictures on RIGHT or refuse 12 or 5); pref 17 substituted into aux1; "a pref that writes a parameter into such a
    # field ... is 5 at a PLAY, PLAY-AFTER, RETARGET ... whose parameter there is not 0"; "a PIECES kind 0 or SHRINK segment
    # in a program without OWN is 5 at ... a PLAY-AFTER" (before "the program carries OWN", 12); "its ease is
    # non-decreasing ... checked at the walk on every non-WAIT segment of the track (5), before the one-non-WAIT-segment
    # shape (12)"; "it moves pixels unless it is PIECES with f1 = 0 ... SHRINK with both axes unscaled" (still forms under a
    # later track: taken); §7.5 "Both window rules are decided in the walk at the record ... never at a SET".
    c = Cache2()
    pic = c.add(image2(40, 30, pattern(40, 30, 7301)))
    strip = c.add(image2(60, 20, pattern(60, 20, 7302)))
    fall = c.add(easing([1500, 1200, 3000, 4096]))                           # E falls 1500 → 1200
    gA = c.add(glyph(6, 10, pattern(6, 10, 7303)))
    fA = c.add(font224([gA] * 224))
    alA = c.add(alphabet([65, 66, 67]))
    ctab = c.add(cell_table([c.add(cell_list(alA, 1, [cell(0, 0, 8, 12, fA, 65, 66, 3), cell(20, 0, 8, 12, fA, 67, 65, 5)]))]))
    rain2 = c.add(rain_rec(6, 3, 8, 12, fA, alA, reveal=2, fill=4))
    O = owner(c)
    sh_bad = gp(c, track(rect(100, 100, 200, 100), BD_NONE, sh(4, h=(100, 40), lvl=(15, 16))), fl=OWN)   # SHRINK's level 16: 12
    sh_ok = gp(c, track(rect(100, 100, 200, 100), BD_NONE, sh(4, h=(100, 40), lvl=(15, 15))), fl=OWN)
    rside = [
        gp(c, track(rect(20, 20, 200, 60), 2, d2(4, pic, 30, 30, dx=60, xR=150, dxR=-40))),                    # DRAW
        gp(c, track(rect(240, 20, 200, 60), 3, r2(4, LEFT, K_RECORD, 250, 30, src=pic, xR=380, dxR=7))),        # REVEAL
        gp(c, track(rect(460, 20, 160, 60), 4, pc(4, K_NEW, dx=20, dxR=-30, dy=5, xR=9)), fl=OWN),             # PIECES uniform
        gp(c, track(rect(20, 100, 160, 80), 2, pc(4, K_NEW, grid=(4, 2), model=RADIAL, x=100, y=140, xR=60, dxR=12,
                                                    f=(0, 2048))), fl=OWN),                                    # PIECES radial (12)
        gp(c, track(rect(200, 100, 160, 80), BD_NONE, pc(4, K_OLD, dxR=9, vacate=3)),
           track(rect(250, 120, 40, 20), 6, f2(4, 9)), fl=OWN),                                                # still: dxR (12)
        gp(c, track(rect(380, 100, 200, 80), 1, gly(4, CELLS, ctab, 0, x=4, xR=40, dx=10, dxR=-20, seed=5))),  # CELLS
        gp(c, track(rect(20, 200, 200, 80), BD_NONE, gly(4, RAIN, rain2, xR=15, dxR=7, seed=6)), fl=OWN),      # rain in place (12)
        gp(c, track(rect(240, 200, 160, 80), BD_NONE, sh(4, h=(80, 30), xR=11, dxR=-5)), fl=OWN)]              # SHRINK (5)
    p17 = gp(c, track(rect(20, 300, 200, 40), 2, d2(4, strip, 30, 305, pref0=pref(0, F_AUX1))))   # aux1 = P0: the window's height
    pa_own = gp(c, track(rect(300, 300, 100, 40), 2, f2(4, 3, pref0=pref(0, F_W1))), fl=OWN)    # P0 into FILL's w1 (unused)
    rt_f = gp(c, track(rect(420, 300, 100, 40), 2, f2(4, 3, pref0=pref(0, F_W1))))
    npa = bp(c, track(rect(100, 380, 200, 60), BD_NONE, pc(4, K_OLD, dx=20, vacate=3)))         # in place, no OWN
    f2s = bp(c, track(rect(320, 380, 200, 60), BD_NONE, pc(4, K_OLD, dx=20, vacate=3),
                      pc(4, K_OLD, dx=20, ease=TABLE, table=fall, vacate=3)), fl=OWN)          # the second segment's ease falls
    still_f = gp(c, track(rect(100, 100, 200, 100), BD_NONE, pc(4, K_OLD, dx=40, f=(0, 0), vacate=2)),
                 track(rect(150, 120, 60, 30), 6, f2(4, 9)), fl=OWN)                            # PIECES with f1 = 0 is still
    still_s = gp(c, track(rect(100, 100, 200, 100), BD_NONE, sh(4)), track(rect(150, 120, 60, 30), 6, f2(4, 9)), fl=OWN)   # SHRINK unscaled
    wpast = bp(c, track(rect(20, 300, 200, 40), 2, d2(4, strip, 30, 305, aux0=15, aux1=10)))    # rows 15..24 of 20: past it
    wzero = bp(c, track(rect(20, 300, 200, 40), 2, d2(4, strip, 30, 305, aux0=3, aux1=0)))      # aux1 0 with aux0 3
    st = [arm(c)]
    t = 2000
    st += [step(tk(t), msg(play(0, P, sh_bad, 0x7E01))),                   # 12
           step(tk(t + 5), msg(play(0, P, sh_ok, 0x7E02))),                # taken
           step(tk(t + 5 + 3 * P), wake()),
           step(tk(t + 15 + 3 * P), msg(close(0xFF)))]
    t += 300
    for i, p_ in enumerate(rside):
        st += [step(tk(t), msg(play(1, P, p_, 0x7E10 + i)))] + [step(tk(t + k * P), wake()) for k in (1, 3)]
        st += [step(tk(t + 3 * P + 10), msg(close(0xFF)))]
        t += 200
    st += [step(tk(t), msg(play(0, P, p17, 0x7E20, P=(5,)))),              # the window's rows 0..4 (aux1 left 0: the whole record)
           step(tk(t + 3 * P), wake()),
           step(tk(t + 3 * P + 10), msg(close(0xFF)))]
    t += 200
    st += [step(tk(t), msg(play(0, P, O, 0x7E30))),
           step(tk(t + 5), msg(play_after(1, P, pa_own, 0x7E31, 0, P=(3,)))),   # a parameter into an unused field: 5
           step(tk(t + 10), msg(play_after(1, P, pa_own, 0x7E32, 0, P=(0,)))),  # its parameter 0: taken
           step(tk(t + 15), msg(play_after(2, P, npa, 0x7E33, 0))),        # in place without OWN: 5 (the OWN test's 12 comes later)
           step(tk(t + 20), msg(close(0xFF))),
           step(tk(t + 25), msg(play(2, P, rt_f, 0x7E34, P=(0,)))),
           step(tk(t + 30), msg(retarget(2, (3,)))),                       # a RETARGET's parameter into the unused field: 5, unchanged
           step(tk(t + 35), msg(retarget(2, (0,)))),                       # 0: restarted
           step(tk(t + 40), msg(close(0xFF))),
           step(tk(t + 45), msg(play(0, P, f2s, 0x7E35))),                 # the second segment's falling ease: 5 (the shape's 12 later)
           step(tk(t + 50), msg(play(0, P, still_f, 0x7E36))),             # a later track over PIECES with f1 = 0: taken (moving: 12)
           step(tk(t + 50 + 3 * P), wake()),
           step(tk(t + 60 + 3 * P), msg(play(0, P, still_s, 0x7E37))),     # over SHRINK unscaled: taken (moving: 12)
           step(tk(t + 60 + 6 * P), wake()),
           step(tk(t + 70 + 6 * P), msg(close(0xFF)))]
    t += 600
    st += [step(tk(t), page(True), msg(bind_list(1, NONE, 8, 0, 10, 2)),
                msg(bind_set(2, ANY, UP, 1, 1, 3, P, wpast))),             # a window past its record: taken at the SET
           step(tk(t + 5), inp(RING, UP)),                                 # the fire's walk: 5
           step(tk(t + 10), msg(bind_set(2, ANY, UP, 1, 1, 3, P, wzero))), # aux1 0 with aux0 3: taken at the SET
           step(tk(t + 15), inp(RING, UP))]                                # 5
    vec("v5-x2-order", st)

    # ---------------------------------------------------------------------------------------------------------------
    # v5-x2-cells — §7.6 "Read range: the program, the cell table and the list aux0 selects — no other list of the table is
    # followed or read — ... every font from which the walk reads a glyph entry ... — a font named only through codes 0
    # and 32 is not read and not in the range ... A cache write that meets it closes the instance (§5.6)"; §5.6's range
    # `[lowest, highest)`. The list aux0 does not select and a font named only through codes 0 and 32 lie below every
    # byte the instance reads: a write over each leaves it drawing to its step T (in the range: closed, outcome 1); a
    # write over the font it reads closes it.
    c = Cache2()
    g2 = c.add(glyph(6, 10, pattern(6, 10, 7401)))
    F2 = c.add(font224([g2] * 224))                                          # named by a cell only through codes 0 and 32
    al1 = c.add(alphabet([66]))
    l1_b = cell_list(al1, 1, [cell(0, 0, 8, 12, F2, 66, 66, 2)])
    l1 = c.add(l1_b)                                                         # the list aux0 does not select
    g1 = c.add(glyph(6, 10, pattern(6, 10, 7402)))
    F1_b = font224([g1] * 224)
    F1 = c.add(F1_b)
    al = c.add(alphabet([32]))
    l0 = c.add(cell_list(al, 1, [cell(0, 0, 8, 12, F1, 65, 66, 3), cell(12, 0, 8, 12, F2, 0, 32, 4)]))
    ct = c.add(cell_table([l0, l1]))
    cp = gp(c, track(rect(100, 100, 120, 40), 1, gly(6, CELLS, ct, 0, x=4, y=4, seed=9)))
    F2_b = font224([g2] * 224)
    st = [arm(c)]
    t = 2000
    st += [step(tk(t), msg(play(0, P, cp, 0x7F01))),
           step(tk(t + 5), msg(cache_write2((l1, l1_b)))),                  # the other list: not read, the instance draws on
           step(tk(t + 10), msg(cache_write2((F2, F2_b)))),                 # the font of codes 0 and 32: not read, draws on
           *[step(tk(t + k * P), wake()) for k in range(1, 6)],            # to its step T: outcome 0
           step(tk(t + 6 * P), msg(close(0xFF))),
           step(tk(t + 300), msg(play(0, P, cp, 0x7F02))),
           step(tk(t + 305), msg(cache_write2((F1, F1_b)))),                # the font it reads: closed (outcome 1)
           step(tk(t + 305 + P), wake())]
    vec("v5-x2-cells", st)

    # ---------------------------------------------------------------------------------------------------------------
    # v5-x2-ring — §7.8 "a plain LIST clears it; the empty table (§6.3) clears it": a ring list wraps; after a release point
    # the first mode 27 empties the table (§6.3: count 0); a fire on the list it names is the edge (c' = 1 is not below a
    # count of 0). Read with RING kept, the fire takes c' modulo a count of 0. (This row tells the readings apart by a
    # fault on the other side only.)
    c = Cache2()
    rp = gp(c, track(rect(100, 100, 200, 40), 2, f2(3, 7)))
    st = [arm(c)]
    t = 2000
    st += [step(tk(t), page(True), msg(bind_ring(0, NONE, 4, 0, 10, 1)), msg(bind_set(0, ANY, UP, 0, 1, 0, P, rp)))]
    st += [step(tk(t + 10 * k), inp(RING, UP)) for k in range(1, 5)]       # cursor 1, 2, 3, then 0: wrapped (field 18)
    st += [step(tk(t + 50), {"lease": "release"}),                          # a release point: the table empty, RING with it
           step(stock()),
           step(tk(t + 60), {"lease": "acquire"}, flags(FULL5), page(True), msg(kf), *writes(c)),
           step(tk(t + 70), msg(bind_set(0, ANY, UP, 0, 1, 0, P, rp))),    # the first mode 27: the table emptied (list 0's count 0)
           step(tk(t + 75), inp(RING, UP))]                                # the edge
    vec("v5-x2-ring", st)

    # ---------------------------------------------------------------------------------------------------------------
    # v5-x2-retarget — §7.5 "RETARGET's restart path clamps W to 1..2048 and restarts it from the width the track last
    # drew — the source's own width when it drew unscaled, as C2's H from the source's height". A DRAW drawn unscaled
    # (w0 = w1 = 0 by its parameters), then a RETARGET to w0 5, w1 20: W eases from the record's 40 to 20 (from w0's 5: other
    # panels). The form whose RETARGET also changes the record (src by a parameter) is not here: a segment holds two prefs,
    # and the unscaled-to-scaled change needs both for w0 and w1.
    c = Cache2()
    rec = c.add(image2(40, 20, pattern(40, 20, 7501)))
    rw = gp(c, track(rect(100, 100, 300, 60), 2, d2(6, rec, 110, 110, dx=120, pref0=pref(0, F_W0), pref1=pref(1, F_W1))))
    st = [arm(c)]
    t = 2000
    st += [step(tk(t), msg(play(0, P, rw, 0x7F11, P=(0, 0)))),             # unscaled
           step(tk(t + P), wake()), step(tk(t + 2 * P), wake()),
           step(tk(t + 2 * P + 5), msg(retarget(0, (5, 20))))] + \
          [step(tk(t + 2 * P + 5 + k * P), wake()) for k in range(1, 6)] + \
          [step(tk(t + 8 * P + 20), msg(close(0xFF)))]
    vec("v5-x2-retarget", st)

    for vec_ in v:
        vec_["contract"] = 2
        vec_["start"] = "zero"
        vec_["panel"] = True
        vec_["features"] = [FEATURES_C3]
    return v

def vectors_v5_x3(kf):
    """Stage X3D's hand-made C3 input (`C3-decisions.md` AP (2); the X3D brief's item 2): one NEW file, `v5-x3-hint` — the
    94 earlier files stay byte-identical (the shipped programs, `v5-ship-*`, come from the Kotlin `ShipVectors`). §7.3
    Steps, as built (the second review pass, 2026-11-20): "a present whose copy left an owned rect out leaves the panel's
    stale mark set, so while any instance owns, every later present's copy is whole whatever its hint says". v5-own's
    hinted steps hint exactly the rows they change, so a whole and a hinted present gave the same P there; here each
    hint misses the rows its fill changed, so the two readings give different panels while the owner is open, and the
    same once it is closed. The forms are `vectors_v5`'s."""
    v = []
    P = 34                                                        # §5.3's starting period
    c = Cache2()
    hold = c.add(prog2(track(rect(360, 80, 160, 100), 5, w2(3)), fl=OWN))   # T 3, no LAND: done and owning, level 5 held in rows 80–179
    st = [step(tk(1000), {"lease": "acquire"}, flags(FULL5), page(True), msg(kf), *[msg(m) for m in c.messages()])]
    t = 2000
    st += [step(tk(t), msg(play(0, P, hold, 0x7301))),                     # 1  owns its rect; this present draws step 1
           step(tk(t + P), wake()),                                         # 2  step 2
           step(tk(t + 2 * P), wake()),                                     # 3  step 3 = T: done and owning; its present marks the panel stale
           step(tk(t + 2 * P + 10), msg(batch(fill(rect(0, 200, 640, 10), 3), hint(200, 209)))),   # 4  whole in either reading (the last step's mark); its copy leaves the owned rect out
           step(tk(t + 2 * P + 20), msg(batch(fill(rect(0, 300, 640, 10), 7), hint(400, 409)))),   # 5  the hint misses rows 300–309: whole while an instance owns (as built), so they reach the lens (honoured: they would not)
           step(tk(t + 2 * P + 30), msg(batch(fill(rect(0, 320, 640, 10), 9), hint(420, 429)))),   # 6  every later present: whole again (honoured: rows 320–329 off the lens too)
           step(tk(t + 2 * P + 40), msg(close(0))),                         # 7  the owner closed (a done owner: no notify); its present copies the shadow whole
           step(tk(t + 2 * P + 50), msg(batch(fill(rect(0, 340, 640, 10), 11), hint(440, 449))))]  # 8  nothing owns: the hint is honoured (C2's rule) — rows 340–349 stay off the lens
    v.append({"name": "v5-x3-hint", "steps": st})
    for vec_ in v:
        vec_["contract"] = 2
        vec_["start"] = "zero"
        vec_["panel"] = True
        vec_["features"] = [FEATURES_C3]
    return v

# ---- C3 DW-3: the wheel (the app switcher bound on the glasses as list 2; the DW spec §2.1–§2.5, §7.2) ----------------
WHEEL_GEO = {"panel": [200, 154, 240, 176], "d": 8, "bandTop": 198, "bandBot": 286, "hN": 20, "yA": 158, "yB": 306}
"""The wheel at rest at layout height 480, Depth 8 — read off the shell's own down program in the committed
`v5-ship-list1-select-level-depth-480` (step 0): the panel (x, y, w, h), the neighbour rows' disparity d, the centre band
[bandTop, bandBot) with its rules, the neighbour form's height hN and the neighbour rows' tops yA (above), yB (below)."""
WHEEL_SWAP_CENTRE = 4          # the DW spec §2.1 (decisions BK (3)): a swapping centre form scaled to the neighbour form's level
WHEEL_RULE_LEVEL = 3           # the rules' level (the switcher's inner rules)

def jround(x):
    """Java's `Math.round` of a double: the nearest integer, a tie toward +∞ (⌊x + ½⌋ at these magnitudes)."""
    import math
    return math.floor(x + 0.5)

def wheel_tables(t):
    """The drum's two ease tables over t steps (the DW spec §2.1): n1 the first step 1..t−1 whose ease-out reaches 2048
    (else t − 1); E1 the ease-out's first n1 values rescaled to end at 4096, E2 its last t − n1 rescaled from 0."""
    eo = [0] + [4096 - (4096 * (t - j) * (t - j)) // (t * t) for j in range(1, t + 1)]     # §5.4's ease-out E[j]
    n1 = next(j for j in range(1, t) if eo[j] >= 2048 or j == t - 1)
    e1 = [jround(4096.0 * eo[i + 1] / eo[n1]) for i in range(n1)]
    e2 = [jround(4096.0 * (eo[n1 + i + 1] - eo[n1]) / (4096 - eo[n1])) for i in range(t - n1)]
    return e1, e2

def wheel_prog(g, cell4, rule4, stp, t, tables):
    """One notch's program (the DW spec §2.2, track by track): stp +1 down (event 4), −1 up (event 5, the mirror); seven
    per-lens tracks over t steps, the swapping centre and neighbour on the tables (off4, values) E1 then E2. Every DRAW
    of a cell takes its record from a kind-1 parameter (P0 the row leaving, P1 the centre leaving, P2 the row arriving
    in the centre, P3 the row entering) through a constant source window (the centre form's rows 0..hC−1 or the
    neighbour form's hC..hC+hN−1); x per lens is left / right: the panel's plane at p.x ∓ d, the screen plane at p.x."""
    px, py, pw, ph = g["panel"]
    d, top, bot, hN, yA, yB = g["d"], g["bandTop"], g["bandBot"], g["hN"], g["yA"], g["yB"]
    hC = bot - top - 2
    y0, y1 = py + 2, py + ph - 2                                   # inside the outer rules
    area = (rect(px - d, y0, pw + d, y1 - y0), rect(px, y0, pw + d, y1 - y0))
    xl, xr = px - d, px + d
    down = stp > 0
    TR = 0x10                                                      # DRAW opt bit 4: a source 0 not drawn
    def src(i): return pref(i, F_SRC)
    cTop = top + 2
    toY, fromY = (yA, yB) if down else (yB, yA)
    tr = []
    # W: the row leaving, squashed into the outer rule and faded to 0 (anchors 0 / 2); the level-0 backdrop paints the area
    tr.append(track(area, 0, d2(t, cell4, xl, yA, dy=y0 - yA, lvl=(15, 0), opt=TR, xR=xr, ease=EASE_OUT, pref0=src(0),
                                h0=hN, h1=1, anchor=0, aux0=hC, aux1=hN) if down else
                    d2(t, cell4, xl, yB, dy=(y1 - hN) - yB, lvl=(15, 0), opt=TR, xR=xr, ease=EASE_OUT, pref0=src(0),
                       h0=hN, h1=1, anchor=2, aux0=hC, aux1=hN)))
    # Z: the row entering, grown out of the other outer rule from level 0
    tr.append(track(area, BD_NONE, d2(t, cell4, xl, y1 - hN, dy=yB - (y1 - hN), lvl=(0, 15), opt=TR, xR=xr, ease=EASE_OUT,
                                      pref0=src(3), h0=1, h1=hN, anchor=2, aux0=hC, aux1=hN) if down else
                    d2(t, cell4, xl, y0, dy=yA - y0, lvl=(0, 15), opt=TR, xR=xr, ease=EASE_OUT, pref0=src(3),
                       h0=1, h1=hN, anchor=0, aux0=hC, aux1=hN)))
    (e1o, e1), (e2o, e2) = tables
    n1, n2 = len(e1), len(e2)
    eo = [0] + [4096 - (4096 * (t - j) * (t - j)) // (t * t) for j in range(1, t + 1)]
    f = eo[n1] / 4096.0
    dx1 = jround(d * f)
    cC = cTop + hC // 2                                            # the centres at the swap, as one ease-out over t puts them
    mX = jround(cC + (toY + hN // 2 - cC) * f)
    mY = jround(fromY + hN // 2 + (cC - (fromY + hN // 2)) * f)
    # X: the centre flattens (anchor 1) and dims to the neighbour form's level, back in depth; at the swap its neighbour form
    tr.append(track(area, BD_NONE,
                    d2(n1, cell4, px, cTop, dx=-dx1, dy=(mX - hC // 2) - cTop, lvl=(15, WHEEL_SWAP_CENTRE), opt=TR, xR=px,
                       dxR=dx1, ease=TABLE, table=e1o, pref0=src(1), h0=hC, h1=hC // 2, anchor=1, aux0=0, aux1=hC),
                    d2(n2, cell4, px - dx1, mX - hN // 2, dx=-(d - dx1), dy=toY - (mX - hN // 2), lvl=(15, 15), opt=TR,
                       xR=px + dx1, dxR=d - dx1, ease=TABLE, table=e2o, pref0=src(1), aux0=hC, aux1=hN)))
    # Y: the neighbour rises to the centre; at the swap its centre form grows (anchor 1), comes forward and brightens
    tr.append(track(area, BD_NONE,
                    d2(n1, cell4, xl, fromY, dx=dx1, dy=(mY - hN // 2) - fromY, lvl=(15, 15), opt=TR, xR=xr, dxR=-dx1,
                       ease=TABLE, table=e1o, pref0=src(2), aux0=hC, aux1=hN),
                    d2(n2, cell4, xl + dx1, mY - hC // 2, dx=d - dx1, dy=cTop - (mY - hC // 2), lvl=(WHEEL_SWAP_CENTRE, 15),
                       opt=TR, xR=xr - dx1, dxR=-(d - dx1), ease=TABLE, table=e2o, pref0=src(2), h0=hC // 2, h1=hC, anchor=1,
                       aux0=0, aux1=hC)))
    if down:   # A: the top inner rule leaves into the outer rule, clipped out of the area at step t; B rises; C comes up
        tr += [track(area, BD_NONE, d2(t, rule4, px, top, dx=-d, dy=(y0 - 2) - top, lvl=(15, 0), opt=TR, xR=px, dxR=d, ease=EASE_OUT)),
               track(area, BD_NONE, d2(t, rule4, xl, bot, dx=d, dy=top - bot, opt=TR, xR=xr, dxR=-d, ease=EASE_OUT)),
               track(area, BD_NONE, d2(t, rule4, xl, y1, dy=bot - y1, lvl=(0, 15), opt=TR, xR=xr, ease=EASE_OUT))]
    else:      # A': the bottom inner rule leaves into the bottom edge; B' sinks; C' comes down from the outer rule (from y0 − 2)
        tr += [track(area, BD_NONE, d2(t, rule4, xl, bot, dy=y1 - bot, lvl=(15, 0), opt=TR, xR=xr, ease=EASE_OUT)),
               track(area, BD_NONE, d2(t, rule4, px, top, dx=-d, dy=bot - top, opt=TR, xR=px, dxR=d, ease=EASE_OUT)),
               track(area, BD_NONE, d2(t, rule4, xl, y0 - 2, dx=d, dy=top - (y0 - 2), lvl=(0, 15), opt=TR, xR=xr, dxR=-d, ease=EASE_OUT))]
    return prog2(*tr)

def wheel_cell(i, w, hC, hN):
    """Item i's cell (w × (hC + hN)): its centre form over its neighbour form, each a bar whose width and level name it."""
    px = [0] * (w * (hC + hN))
    def bar(x0, y0, bw, bh, lvl):
        for y in range(y0, y0 + bh): px[y * w + x0:y * w + x0 + bw] = [lvl] * bw
    bar(16, 20, 60 + 16 * i, 40, 15 - i % 8)
    bar(24, hC + 4, 30 + 10 * i, 12, 4 + i % 3)
    return px

def vectors_v5_dw(kf):
    """C3 stage DW-3's hand-made input (decisions DQ (7); the DW spec §7.2): one NEW file, `v5-dw-fire` — the earlier
    files stay byte-identical (the wheel's shipped acts, `v5-ship-wheel-*`, come from the Kotlin `ShipVectors`). SETs of
    both wheel programs on LIST-RING lists of count 9 and 3: fires mid-list and at both wraps, a burst of three closing
    each other, the COMMIT of each report's parameters (END in the shadow), a report-only tap at both ends, a tap refused
    17 after an uncommitted notch, a LEFT-only input then SETTLE. The programs are `wheel_prog`'s, written from the DW
    spec's tracks; the vector's "wheel" key names each one's `Wheel.program` parameters, and `Version2MotionTest` pins
    that the product's builder called with them gives these bytes (a difference is a finding, never an edit). S as RIGHT
    counts it; cursors and parameters as §7.8 computes them, for the COMMITs' addresses only."""
    v = []
    P, T = 16, 19                                    # the DW spec §2.5 (modeled): 16 ticks, 19 steps at 480
    g = WHEEL_GEO
    hC, hN, w = g["bandBot"] - g["bandTop"] - 2, g["hN"], g["panel"][2]
    c, s = Cache2(), Seq()
    rule4 = c.add(image2(w, 2, [WHEEL_RULE_LEVEL] * (2 * w)))
    cells = [c.add(image2(w, hC + hN, wheel_cell(i, w, hC, hN))) for i in range(9)]
    items9 = cells
    items3 = [cells[4], cells[7], cells[1]]
    itab9 = c.add(b"".join(u16(o) for o in items9))
    itab3 = c.add(b"".join(u16(o) for o in items3))
    e1, e2 = wheel_tables(T)
    e1o, e2o = c.add(easing(e1)), c.add(easing(e2))
    down4 = c.add(wheel_prog(g, cells[0], rule4, 1, T, ((e1o, e1), (e2o, e2))))
    up4 = c.add(wheel_prog(g, cells[0], rule4, -1, T, ((e1o, e1), (e2o, e2))))
    rec_len = lambda o4: next(len(d) for a, d in c.entries if a == o4)
    wheel = {"geo": g, "programs": [
        {"at4": at4, "step": stp, "t": T, "cell4": cells[0], "cellBytes": rec_len(cells[0]), "rule4": rule4,
         "ruleBytes": rec_len(rule4), "tables": [e1o, e2o], "none": False} for at4, stp in ((down4, 1), (up4, -1))]}
    ARGS = {1: (-2, -1, 0, 1), -1: (2, 1, 0, -1)}    # the DW spec §2.2: every parameter kind 1
    def params(items, cur, stp):
        """§7.8 on a ring list: v = cursor + step, c' = v mod n, Pi = the item at (v + arg) mod n."""
        vv = cur + stp
        return vv % len(items), tuple(items[(vv + a) % len(items)] for a in ARGS[stp])
    def sets(lst, slot):
        return [msg(bind_set(6, ANY, UP, lst, 1, slot, P, down4, [(K_ITEM, a) for a in ARGS[1]])),
                msg(bind_set(7, ANY, DOWN, lst, -1, slot, P, up4, [(K_ITEM, a) for a in ARGS[-1]])),
                msg(bind_set(0, RING, TAP, lst, 0, slot, P, NONE))]          # the report-only tap (`Taps · report`, §6.3)
    def run(t_, ev, wakes=(T // 2, T - 1), only=None):
        """A matched event at t_ (S + 1 on the lenses it reaches), the wake that draws step 1, then wakes at those steps."""
        if only is None: s.fire()
        return ([step(tk(t_), inp(RING, ev, only=only)), step(tk(t_), wake())]
                + [step(tk(t_ + k * P), wake()) for k in wakes])
    st = [step(tk(1000), {"lease": "acquire"}, flags(FULL5), page(True), msg(kf), *[msg(m) for m in c.messages()])]
    # list 2 in slot 0, count 9, a ring from cursor 0 shifted to 4; its two scroll entries and the tap (the shell's order:
    # LIST-RING, then the SETs)
    st += [step(tk(1010), msg(bind_ring(2, itab9, 9, 4, 0, 1)), *sets(2, 0))]
    cur = 4
    # fires mid-list, each answered by the COMMIT of its report's parameters: after T (END over the held last step), and
    # while it draws (adopted: it runs on to T)
    cur, p = params(items9, cur, 1)                                        # at 4: v 5, c' 5 — items 3..6
    st += run(2000, UP) + [step(tk(2000 + T * P + 20), msg(commit(0, s.n, 2, cur, down4, p)))]
    cur, p = params(items9, cur, -1)                                       # at 5: v 4, c' 4 — items 6, 5, 4, 3
    st += run(2600, DOWN, wakes=(4,)) + [step(tk(2600 + 5 * P), msg(commit(0, s.n, 2, cur, up4, p))),
                                         step(tk(2600 + (T - 1) * P), wake())]
    # both wraps: the cursor moved to the last row by a riding LIST-RING's shift, then down past it and back up past row 0
    st += [step(tk(3200), msg(bind_ring(2, itab9, 9, 4, 0, 1)))]            # 4 + 4 = 8
    cur = 8
    cur, p = params(items9, cur, 1)                                        # at 8: v 9, c' 0 — items 7, 8, 0, 1; field 18
    st += run(3300, UP) + [step(tk(3300 + T * P + 20), msg(commit(0, s.n, 2, cur, down4, p)))]
    cur, p = params(items9, cur, -1)                                       # at 0: v −1, c' 8 — items 1, 0, 8, 7; field 18
    st += run(3900, DOWN) + [step(tk(3900 + T * P + 20), msg(commit(0, s.n, 2, cur, up4, p)))]
    # a burst of three closing each other (each fire's close set holds the slot's instance), the last answered
    st += run(4500, DOWN, wakes=())                                        # at 8: c' 7
    st += run(4500 + 2 * P, DOWN, wakes=())                                # c' 6: closes the first at step 2
    cur, p = params(items9, 6, -1)                                         # c' 5: closes the second at step 2 — items 7, 6, 5, 4
    st += run(4500 + 4 * P, DOWN) + [step(tk(4500 + 4 * P + T * P + 20), msg(commit(0, s.n, 2, cur, up4, p)))]
    # count 3: a fresh LIST-RING (a new mark, the item table of three) — (5 − 5) mod 3 = 0
    st += [step(tk(5200), msg(bind_ring(2, itab3, 3, -5, 0, 2)))]
    cur = 0
    st += run(5300, TAP, wakes=())                                         # a report-only tap at row 0: no draw, no parameters
    cur, p = params(items3, cur, -1)                                       # at 0: v −1, c' 2 — items3 1, 0, 2, 1; field 18
    st += run(5400, DOWN) + [step(tk(5400 + T * P + 20), msg(commit(0, s.n, 2, cur, up4, p)))]
    st += run(6000, TAP, wakes=())                                         # a report-only tap at the last row (2)
    cur, p = params(items3, cur, 1)                                        # at 2: v 3, c' 0 — items3 1, 2, 0, 1; field 18
    st += run(6100, UP) + [step(tk(6100 + T * P + 20), msg(commit(0, s.n, 2, cur, down4, p)))]
    # a tap refused 17 after an uncommitted notch: the slot holds a done local; the DISCARD ends it, then the tap reports
    cur, p = params(items3, cur, 1)                                        # at 0: v 1, c' 1 — no answer
    st += run(6700, UP)
    st += run(6700 + T * P + 20, TAP, wakes=())                            # refused 17: nothing drawn, the list as it is
    st += [step(tk(7400), msg(discard(0, (s.n + 1 - PAST) & 0xFFFF, 2, cur)))]   # the local stopped, the cursor := 1
    st += run(7500, TAP, wakes=())                                         # report-only again at row 1
    # a LEFT-only input (a dropped answer, §6.4): LEFT one ahead; the idle SETTLE from RIGHT's S + 1 ends it and realigns
    r_next = (s.n + 1) & 0xFFFF
    st += run(7600, UP, only="L")                                          # LEFT alone: its S one ahead, its c' 2; RIGHT: no 115
    st += [step(tk(7600 + T * P + 20), msg(settle(0, r_next, 2, cur, 0)))]   # LEFT's local (at T, held) stopped; both cursors := 1
    v.append({"name": "v5-dw-fire", "steps": st, "contract": 2, "start": "zero", "panel": True, "features": [FEATURES_C3],
              "wheel": wheel})
    return v

DM_GEO = {"lens": [16, 146, 608, 64], "rowH": 32, "cx": 16, "rowW": 596, "listD": 8, "bandD": 4, "kA": 3, "kB": 3,
          "rows": 8, "margin": 4}
"""Main's list at layout height 352 with his eight rows (a ring list at stage 5, pitch −rowH), read off the product's own
staging (`MainLocalCheck.MainRig(BoxFace, 352)`, Matrix at stage 5): the band (x, y, w, h), the row height, the content's x
and the rows' width (content less the rail), the list's and the band's disparities, the rows above and below the band a
whole-list pan draws, the count, and the ring's margin (rows above + 1). `Version2MotionTest` checks them against the
staging."""
DM_LVL_BACK = 8                # `MainPan.LVL_BACK`: the leaving band form's level at the swap (Depth's band, K1-1)

def dm_matrix_prog(g, strip4, band4, rain4, step, T, swap):
    """Main's Matrix pan (the DM-2 spec §1.5; decisions DR (b), DW 5): five per-lens tracks, version 2, no OWN — T0 the
    rows' and band's whole area W at level 0, waiting T (the backdrop T3 needs); T1 and T2 Depth's rows above and below
    the band (the strip at y ← P0 and y ← P3, one pitch over T, ease 1); T3 a reveal-0 rain over W, backdrop none, its
    seed h0 ← P1 (the entering row's band form, kind 1: both lenses compute it from the cursor) and no tag mixed in (C3
    DM-2aF, decisions EV 1: a local fire's tag is each lens's own S, §6.4); T4 Depth's band — the leaving form (src ← P2) at the band's plane to the list's
    and level 15 → 8 over the swap's steps, then the entering one (src ← P1) from the list's plane to the band's and 8 → 15."""
    lx, ly, lw, lh = g["lens"]
    rh, cx, rw, dL, dB, kA, kB = g["rowH"], g["cx"], g["rowW"], g["listD"], g["bandD"], g["kA"], g["kB"]
    shift, bandW = dL - dB, rw + abs(dL - dB)
    W = (rect(cx - dL, ly - kA * rh, rw, (kA + kB) * rh + lh), rect(cx + dL, ly - kA * rh, rw, (kA + kB) * rh + lh))
    A = (rect(cx - dL, ly - kA * rh, rw, kA * rh), rect(cx + dL, ly - kA * rh, rw, kA * rh))
    B = (rect(cx - dL, ly + lh, rw, kB * rh), rect(cx + dL, ly + lh, rw, kB * rh))
    N = (rect(cx - max(dL, dB), ly, bandW, lh), rect(cx + min(dL, dB), ly, bandW, lh))
    rows = lambda pr: d2(T, strip4, cx - dL, 0, dy=-step * rh, xR=cx + dL, ease=EASE_OUT, pref0=pref(pr, F_Y))
    return prog2(
        track(W, 0, w2(T)),
        track(A, 0, rows(0)),
        track(B, 0, rows(3)),
        track(W, BD_NONE, gly(T, RAIN, rain4, pref0=pref(1, F_H0))),
        track(N, 0, d2(swap, band4, cx - dB, ly, dx=-shift, xR=cx + dB, dxR=shift, lvl=(15, DM_LVL_BACK), ease=EASE_OUT,
                       pref0=pref(2, F_SRC)),
                    d2(T - swap, band4, cx - dL, ly, dx=shift, xR=cx + dL, dxR=-shift, lvl=(DM_LVL_BACK, 15), ease=EASE_OUT,
                       pref0=pref(1, F_SRC))))

def vectors_v5_dm(kf):
    """C3 stage DM-2a's hand-made input (decisions DW 6, 7; the DM spec §5's vector): one NEW file, `v5-dm-rain-fire` —
    every earlier file stays byte-identical (Main's Matrix ship act, `v5-ship-ring-matrix-352`, comes from the Kotlin
    `ShipVectors`). Main's own Matrix pans at 352 on its ring list of eight (pitch −rowH, P 35, T 8 — the staging's fast-path
    fit, modeled): a reveal-0 rain inside a WAIT-only level track over moving DRAW tracks, fired and COMMITted — mid-list
    after T (the last step drawn: it lights nothing), while it draws (adopted), both wraps, a burst of two closing each
    other — then a SET of the same pan naming a reveal-1 rain record, accepted (a SET walks no named record) and refused
    5 at the fire (an in-place track outside OWN), and the real pan SET again and fired. The strip, band forms and glyphs
    are the vector's own pixels at the staging's sizes; the "matrix" key names the parameters, and `Version2MotionTest`
    pins that the product's builder called with them gives these program and rain bytes (a difference is a finding,
    never an edit). S as RIGHT counts it; cursors and parameters as §7.8 computes them on a ring, for the COMMITs only."""
    v = []
    g = DM_GEO
    P, T, swap = 35, 8, 3                            # the staging's period and steps at 352 (modeled) and `ListStaging.swapOf(8)`
    lx, ly, lw, lh = g["lens"]
    rh, rw, n, mg = g["rowH"], g["rowW"], g["rows"], g["margin"]
    c, s = Cache2(), Seq()
    # the strip: the eight rows with the ring's margins (4 + 8 + 4 rows), each a level with a mark that moves along x
    sh_ = (n + 2 * mg) * rh
    strip_px = []
    for y in range(sh_):
        r, iy = y // rh, y % rh
        row = [0] * rw if iy >= rh - 6 else [2 + r % 12] * rw
        if iy < rh - 6:
            for x in range(20 + 30 * r, 60 + 30 * r): row[x] = 15
        strip_px += row
    strip4 = c.add(image2(rw, sh_, strip_px))
    def band_form(i):
        px = []
        for y in range(lh):
            row = [4 + i] * rw
            if 16 <= y < 48:
                for x in range(30 * i, 30 * i + 100): row[x] = 15
            px += row
        return image2(rw, lh, px)
    items = [c.add(band_form(i)) for i in range(n)]
    itab = c.add(b"".join(u16(o) for o in items))
    codes = b"0123456789ABCDEF"
    gs = [c.add(glyph(11, 27, pattern(11, 27, 900 + i))) for i in range(len(codes))]
    ent = [gs[0]] * 224
    for i, ch in enumerate(codes): ent[ch - 32] = gs[i]
    font4 = c.add(font224(ent))
    alpha4 = c.add(alphabet(list(codes)))
    cols = -(-rw // 24)                              # ⌈rowW / cw⌉
    spread = -(-((g["kA"] + g["kB"]) * rh + lh) // 27)   # ⌈the rain's height / ch⌉
    rain4 = c.add(rain_rec(cols, 6, 24, 27, font4, alpha4, head=15, tail=3, reveal=0, fill=0, spread=spread, flicker=2))
    rain1 = c.add(rain_rec(cols, 6, 24, 27, font4, alpha4, head=15, tail=3, reveal=1, fill=0, spread=spread, flicker=2))
    down4 = c.add(dm_matrix_prog(g, strip4, items[0], rain4, 1, T, swap))
    up4 = c.add(dm_matrix_prog(g, strip4, items[0], rain4, -1, T, swap))
    rev4 = c.add(dm_matrix_prog(g, strip4, items[0], rain1, 1, T, swap))
    def rules(stp):                                  # P0, P3 kind 2 (y = v × pitch + arg), P1 the entering row's form, P2 the leaving one's
        return [(K_PITCH, ly + (stp - mg) * rh), (K_ITEM, 0), (K_ITEM, -stp), (K_PITCH, ly + lh + (stp - 1 - mg) * rh)]
    rec_len = lambda o4: next(len(d) for a, d in c.entries if a == o4)
    matrix = {"height": 352, "geo": g, "period": P,
              "strip": [strip4, rw, sh_, rec_len(strip4)], "band": [items[0], rw, lh, rec_len(items[0])], "items": items,
              "rain4": rain4, "font4": font4, "alpha4": alpha4,
              "programs": [{"at4": at4, "step": stp, "t": T, "rain4": r4, "rules": [list(x) for x in rules(stp)]}
                           for at4, stp, r4 in ((down4, 1, rain4), (up4, -1, rain4), (rev4, 1, rain1))]}
    def params(cur, stp):
        """§7.8 on a ring list: v = cursor + step, c' = v mod n; kind 1 the item at (v + arg) mod n, kind 2 v × pitch + arg."""
        vv = cur + stp
        return vv % n, tuple(items[(vv + a) % n] if k == K_ITEM else vv * -rh + a for k, a in rules(stp))
    def sets(down=down4):
        return [msg(bind_set(0, ANY, UP, 0, 1, 0, P, down, rules(1))),
                msg(bind_set(1, ANY, DOWN, 0, -1, 0, P, up4, rules(-1))),
                msg(bind_set(2, RING, TAP, 0, 0, 0, P, NONE))]          # the report-only tap (`Taps · report`)
    def run(t_, ev, wakes=(T // 2, T - 1, T)):
        """A matched event at t_ (S + 1 on both lenses), the wake that draws step 1, then wakes at those steps."""
        s.fire()
        return ([step(tk(t_), inp(RING, ev)), step(tk(t_), wake())]
                + [step(tk(t_ + k * P), wake()) for k in wakes])
    st = [step(tk(1000), {"lease": "acquire"}, flags(FULL5), page(True), msg(kf), *[msg(m) for m in c.messages()])]
    # Main's list in slot 0: LIST-RING of eight at pitch −rowH from cursor 0, then its SETs (the shell's order)
    st += [step(tk(1010), msg(bind_ring(0, itab, n, 0, -rh, 1)), *sets())]
    cur = 0
    # mid-list: a notch down answered after T (the last step drawn and held: the rain gone, END), one up answered while
    # it draws (adopted: it runs on to T)
    cur, p = params(cur, 1)                                                # at 0: v 1, c' 1
    st += run(2000, UP) + [step(tk(2000 + T * P + 20), msg(commit(0, s.n, 0, cur, down4, p)))]
    cur, p = params(cur, -1)                                               # at 1: v 0, c' 0
    st += run(2600, DOWN, wakes=(4,)) + [step(tk(2600 + 5 * P), msg(commit(0, s.n, 0, cur, up4, p))),
                                         step(tk(2600 + (T - 1) * P), wake()), step(tk(2600 + T * P), wake())]
    # both wraps: up past row 0 to the last row (v −1), then down past it back to row 0 (v 8)
    cur, p = params(cur, -1)                                               # at 0: v −1, c' 7; field 18
    st += run(3300, DOWN) + [step(tk(3300 + T * P + 20), msg(commit(0, s.n, 0, cur, up4, p)))]
    cur, p = params(cur, 1)                                                # at 7: v 8, c' 0; field 18
    st += run(3900, UP) + [step(tk(3900 + T * P + 20), msg(commit(0, s.n, 0, cur, down4, p)))]
    # a burst of two: the second closes the first at step 2, and is answered
    st += run(4500, UP, wakes=(1,))                                        # at 0: c' 1
    cur, p = params(1, 1)                                                  # c' 2
    st += run(4500 + 2 * P, UP) + [step(tk(4500 + 2 * P + T * P + 20), msg(commit(0, s.n, 0, cur, down4, p)))]
    # the same pan naming a reveal-1 rain record: the SET is accepted (it walks no named record); the fire is 5 (§7.2: an
    # in-place track outside OWN) and the cursor stays; the real pan SET again, then fired and answered
    st += [step(tk(5200), msg(bind_set(0, ANY, UP, 0, 1, 0, P, rev4, rules(1))))]
    st += run(5300, UP, wakes=(1,))                                        # at 2: S + 1, refused 5, nothing drawn
    st += [step(tk(5400), msg(bind_set(0, ANY, UP, 0, 1, 0, P, down4, rules(1))))]
    cur, p = params(cur, 1)                                                # at 2: v 3, c' 3
    st += run(5500, UP) + [step(tk(5500 + T * P + 20), msg(commit(0, s.n, 0, cur, down4, p)))]
    v.append({"name": "v5-dm-rain-fire", "steps": st, "contract": 2, "start": "zero", "panel": True, "features": [FEATURES_C3],
              "matrix": matrix})
    return v

DM_SCRAMBLE_SEED = 0x5343      # the product's `ScrollEffects.SCRAMBLE_SEED`: a constant of ours (decisions EP 7); the vector pins the bytes

def dm_scramble_prog(g, strip4, band4, above4, below4, band_new4, band_old4, step, T):
    """Main's Scramble pan (the DM spec §1.4; the DM-2 spec §1): seven per-lens tracks, version 2, no OWN — T1 and T2 the
    rows above and below the band at level 0 (the strip at y ← P0 and y ← P3, one pitch in ONE step, then held); T3 the
    band at level 0 (the entering form, src ← P1, at the band's plane, one step); T4 and T5 the rows' cells (backdrop
    none, inside T1 and T2: aux0 ← P2, the cursor before the move by kind 3, and y ← P0 / P3) over T; T6 and T7 the band's
    new-role then old-role cells (aux0 ← P2) over T — every cell track seeded with DM_SCRAMBLE_SEED alone, no tag mixed
    in (C3 DM-2bF: opt bit 7 clear, as every program a bound list fires)."""
    lx, ly, lw, lh = g["lens"]
    rh, cx, rw, dL, dB, kA, kB = g["rowH"], g["cx"], g["rowW"], g["listD"], g["bandD"], g["kA"], g["kB"]
    bandW = rw + abs(dL - dB)
    A = (rect(cx - dL, ly - kA * rh, rw, kA * rh), rect(cx + dL, ly - kA * rh, rw, kA * rh))
    B = (rect(cx - dL, ly + lh, rw, kB * rh), rect(cx + dL, ly + lh, rw, kB * rh))
    N = (rect(cx - max(dL, dB), ly, bandW, lh), rect(cx + min(dL, dB), ly, bandW, lh))
    rows = lambda pr: d2(1, strip4, cx - dL, 0, dy=-step * rh, xR=cx + dL, pref0=pref(pr, F_Y))
    rcells = lambda tb4, pr: gly(T, CELLS, tb4, x=cx - dL, xR=cx + dL, seed=DM_SCRAMBLE_SEED,
                                 pref0=pref(2, F_AUX0), pref1=pref(pr, F_Y))
    bcells = lambda tb4: gly(T, CELLS, tb4, x=cx - dB, y=ly, xR=cx + dB, seed=DM_SCRAMBLE_SEED, pref0=pref(2, F_AUX0))
    return prog2(
        track(A, 0, rows(0)),
        track(B, 0, rows(3)),
        track(N, 0, d2(1, band4, cx - dB, ly, xR=cx + dB, pref0=pref(1, F_SRC))),
        track(A, BD_NONE, rcells(above4, 0)),
        track(B, BD_NONE, rcells(below4, 3)),
        track(N, BD_NONE, bcells(band_new4)),
        track(N, BD_NONE, bcells(band_old4)))

def vectors_v5_dm2b(kf):
    """C3 stage DM-2b's hand-made input (decisions EP 4; the standing pattern of DQ (7); the DM-2 spec §1's vector): one
    NEW file, `v5-dm-cells-fire` — every earlier file stays byte-identical (Main's Scramble ship act,
    `v5-ship-ring-scramble-352`, comes from the Kotlin `ShipVectors`). Main's own Scramble pans at 352 on its ring list of
    eight (pitch −rowH; P and T the staging's fast-path fit, modeled): CELLS whose list is chosen by kind 3 (the cursor
    before the move) and placed by kind 2 at −rowH, old = code / new = 32 cells and old = new = 32 masks over level-0
    record tracks, every cell settled on 32 at T — so END at a COMMIT is the record tracks' — fired and COMMITted: mid-list
    after T, one cursor whose down pan names three 512-cell lists (over the cap: refused 12 at the fire, nothing drawn,
    the cursor kept) and the up pan at that cursor drawn (adopted while it draws), both wraps. The strip, band forms,
    glyphs and cells are the vector's own at the staging's sizes; the "scramble" key names the parameters, and
    `Version2MotionTest` pins that the product's builder called with them gives these programs (a difference is a finding,
    never an edit). S as RIGHT counts it; cursors and parameters as §7.8 computes them on a ring, for the COMMITs only."""
    v = []
    g = DM_GEO
    P, T = 37, 8                                     # the staging's period and steps at 352 (modeled; `Version2MotionTest` pins them)
    noise = max(1, T // 3)                           # the product's N = max(1, ⌊T / 3⌋)
    lx, ly, lw, lh = g["lens"]
    rh, rw, n, mg, kA, kB = g["rowH"], g["rowW"], g["rows"], g["margin"], g["kA"], g["kB"]
    c, s = Cache2(), Seq()
    sh_ = (n + 2 * mg) * rh                          # the strip: as v5-dm-rain-fire's (4 + 8 + 4 rows), a level and a mark a row
    strip_px = []
    for y in range(sh_):
        r, iy = y // rh, y % rh
        row = [0] * rw if iy >= rh - 6 else [2 + r % 12] * rw
        if iy < rh - 6:
            for x in range(20 + 30 * r, 60 + 30 * r): row[x] = 15
        strip_px += row
    strip4 = c.add(image2(rw, sh_, strip_px))
    def band_form(i):
        px = []
        for y in range(lh):
            row = [4 + i] * rw
            if 16 <= y < 48:
                for x in range(30 * i, 30 * i + 100): row[x] = 15
            px += row
        return image2(rw, lh, px)
    items = [c.add(band_form(i)) for i in range(n)]
    itab = c.add(b"".join(u16(o) for o in items))
    codes = b"0123456789ABCDEF"
    grecs = [glyph(14, 27, pattern(14, 27, 1300 + i)) for i in range(len(codes))]
    gs = [c.add(r) for r in grecs]
    ent = [gs[0]] * 224
    for i, ch in enumerate(codes): ent[ch - 32] = gs[i]
    font4 = c.add(font224(ent))
    alpha4 = c.add(alphabet(list(codes)))
    code = lambda r, j: codes[(3 * (r % n) + j) % len(codes)]          # row r's three letters
    def row_cells(srow, d):
        """Strip row srow's three letters: masks (old = new = 32) at its final place, then old = code / new = 32 at its start."""
        r = srow - mg
        xs = [20 + 30 * (r % n) + 13 * j for j in range(3)]
        return ([cell(x, (srow - d) * rh + 2, 14, 27, font4, 32, 32, 0) for x in xs],
                [cell(x, srow * rh + 2, 14, 27, font4, code(r, j), 32, 0, 15) for j, x in enumerate(xs)])
    def window_list(srows, d):
        masks, olds = [], []
        for sr in srows:
            m_, o_ = row_cells(sr, d); masks += m_; olds += o_
        return c.add(cell_list(alpha4, noise, masks + olds))
    def big_list(i0):
        """512 cells over the area (the cap's cursor): each counts its box, 16 × the longest alphabet RLE and 10 × its glyph."""
        return c.add(cell_list(alpha4, noise, [cell(4 + 15 * (k % 38), 2 + 3 * (k // 38), 14, 27, font4, codes[(k + i0) % 16], 32, 0)
                                               for k in range(512)]))
    BIG = 1                                          # the cursor whose down pan is over the cap
    tables = {}
    for d in (1, -1):
        above = [big_list(0) if (d == 1 and cur == BIG) else
                 window_list(range(cur + mg - kA - (1 if d < 0 else 0), cur + mg - kA - (1 if d < 0 else 0) + kA + 1), d) for cur in range(n)]
        below = [big_list(5) if (d == 1 and cur == BIG) else
                 window_list(range(cur + mg + (1 if d > 0 else 0), cur + mg + (1 if d > 0 else 0) + kB + 1), d) for cur in range(n)]
        tables[d] = (c.add(cell_table(above)), c.add(cell_table(below)))
    def band_list(r, old):
        xs = [10 + 30 * r + 13 * j for j in range(3)]
        return c.add(cell_list(alpha4, noise, [cell(x, 16, 14, 27, font4, code(r, j) if old else 32, 32, 0) for j, x in enumerate(xs)]))
    band_old4 = c.add(cell_table([band_list(r, True) for r in range(n)]))
    band_new = {d: c.add(cell_table([big_list(9) if (d == 1 and cur == BIG) else band_list((cur + d) % n, False) for cur in range(n)]))
                for d in (1, -1)}
    # the cap's cursor, a lower bound of C from §7.12's CELLS term alone (three lists of 512): past the cap
    lg = max(len(r) - 2 for r in grecs)
    assert 3 * 512 * (64 + 10 * 14 * 27 + 16 * lg) > CAP, "the cap's cursor is not over the cap"
    progs = {d: c.add(dm_scramble_prog(g, strip4, items[0], tables[d][0], tables[d][1], band_new[d], band_old4, d, T)) for d in (1, -1)}
    def rules(stp):                                  # P0, P3 kind 2 (Depth's), P1 the entering row's form, P2 the cursor before the move
        return [(K_PITCH, ly + (stp - mg) * rh), (K_ITEM, 0), (K_CUR, 0), (K_PITCH, ly + lh + (stp - 1 - mg) * rh)]
    rec_len = lambda o4: next(len(dd) for a, dd in c.entries if a == o4)
    scramble = {"height": 352, "geo": g, "period": P, "noise": noise,
                "strip": [strip4, rw, sh_, rec_len(strip4)], "band": [items[0], rw, lh, rec_len(items[0])], "items": items,
                "font4": font4, "alpha4": alpha4, "bandOld4": band_old4, "big": BIG,
                "programs": [{"at4": progs[stp], "step": stp, "t": T, "above4": tables[stp][0], "below4": tables[stp][1],
                              "bandNew4": band_new[stp], "rules": [list(x) for x in rules(stp)]} for stp in (1, -1)]}
    def params(cur, stp):
        """§7.8 on a ring list: v = cursor + step, c' = v mod n; kind 1 the item at (v + arg) mod n, kind 2 v × pitch +
        arg, kind 3 the cursor before the move + arg."""
        vv = cur + stp
        return vv % n, tuple(items[(vv + a) % n] if k == K_ITEM else (cur + a) if k == K_CUR else vv * -rh + a for k, a in rules(stp))
    def run(t_, ev, wakes=(T // 2, T - 1, T)):
        """A matched event at t_ (S + 1 on both lenses), the wake that draws step 1, then wakes at those steps."""
        s.fire()
        return ([step(tk(t_), inp(RING, ev)), step(tk(t_), wake())]
                + [step(tk(t_ + k * P), wake()) for k in wakes])
    st = [step(tk(1000), {"lease": "acquire"}, flags(FULL5), page(True), msg(kf), *[msg(m) for m in c.messages()])]
    # Main's list in slot 0: LIST-RING of eight at pitch −rowH from cursor 0, then its SETs (the shell's order)
    st += [step(tk(1010), msg(bind_ring(0, itab, n, 0, -rh, 1)),
                msg(bind_set(0, ANY, UP, 0, 1, 0, P, progs[1], rules(1))),
                msg(bind_set(1, ANY, DOWN, 0, -1, 0, P, progs[-1], rules(-1))),
                msg(bind_set(2, RING, TAP, 0, 0, 0, P, NONE)))]                 # the report-only tap (`Taps · report`)
    cur = 0
    cur, p = params(cur, 1)                                                # at 0: index 0, v 1, c' 1 — answered after T
    st += run(2000, UP) + [step(tk(2000 + T * P + 20), msg(commit(0, s.n, 0, cur, progs[1], p)))]
    st += run(2700, UP, wakes=(1,))                                        # at 1: the down pan's index 1 names three lists of 512 — refused 12, nothing drawn, the cursor kept
    cur, p = params(cur, -1)                                               # at 1: the up pan's index 1 — drawn, answered while it draws (adopted)
    st += run(3000, DOWN, wakes=(4,)) + [step(tk(3000 + 5 * P), msg(commit(0, s.n, 0, cur, progs[-1], p))),
                                         step(tk(3000 + (T - 1) * P), wake()), step(tk(3000 + T * P), wake())]
    cur, p = params(cur, -1)                                               # at 0: index 0, v −1, c' 7; field 18
    st += run(3700, DOWN) + [step(tk(3700 + T * P + 20), msg(commit(0, s.n, 0, cur, progs[-1], p)))]
    cur, p = params(cur, 1)                                                # at 7: index 7, v 8, c' 0; field 18
    st += run(4400, UP) + [step(tk(4400 + T * P + 20), msg(commit(0, s.n, 0, cur, progs[1], p)))]
    v.append({"name": "v5-dm-cells-fire", "steps": st, "contract": 2, "start": "zero", "panel": True, "features": [FEATURES_C3],
              "scramble": scramble})
    return v

# ---- the self-check (spec §3 D1): every message decodes by its builder's inverse, every program walks to its
# own length. It proves the encoders' lengths, not the semantics (that is the C's and the simulator's).
def walk_prog(buf, off):
    """Walk a program record at byte `off` (§5.4; a version-2 record's 8-B header and 40-B segments, §7.2); return its
    length, raise ValueError when it does not end there."""
    return parse_prog(buf, off)[2]

def entries19(body):
    """A mode-19 entry list without its mode byte → [(off4, data)]; ValueError when an entry runs past it."""
    out, p = [], 0
    while p < len(body):
        if p + 4 > len(body): raise ValueError("an entry header cut short")
        off4, n = body[p] | body[p + 1] << 8, body[p + 2] | body[p + 3] << 8
        if p + 4 + n > len(body): raise ValueError("an entry's data runs past the list")
        out.append((off4, body[p + 4:p + 4 + n])); p += 4 + n
    return out

def walk_msgs(m):
    """Every message a message carries, itself first: a batch's sub-messages and a self-test step's message, read
    leniently (a malformed batch yields what its lengths reach)."""
    yield m
    if len(m) >= 3 and m[0] == 16 and m[1] == 1:
        yield from walk_msgs(m[2:])
    elif len(m) >= 2 and m[0] == 8:
        p = 2
        for _ in range(m[1]):
            if p + 2 > len(m): return
            ln = m[p] | m[p + 1] << 8
            if p + 2 + ln > len(m): return
            yield from walk_msgs(m[p + 2:p + 2 + ln]); p += 2 + ln

def check_neutral(vecs):
    """C2 B.2 / `FIRMWARE.md` §6.10: the v1/v2/v3 sets answer alike on a bit-10 build only while none of them sends
    what bit 10 gives a meaning — mode 27, mode 28 (0x9C included), mode-26 subs 3..5, flag bit 4 — at any depth."""
    n = 0
    for vec in vecs:
        for si, st in enumerate(vec["steps"]):
            for op in st["ops"]:
                where = f"{vec['name']} step {si}"
                assert not ("flags" in op and op["flags"] & BIND), f"{where}: a FLAGS_SET with bit 4"
                assert not ({"input", "page"} & op.keys()), f"{where}: a C2 op"
                if "msg" not in op: continue
                for m in walk_msgs(bytes.fromhex(op["msg"])):
                    n += 1
                    if not m: continue
                    assert m[0] & 0x7F not in (27, 28), f"{where}: mode {m[0]:#x}"
                    assert not (m[0] & 0x7F == 26 and len(m) >= 2 and m[1] in (3, 4, 5)), f"{where}: mode-26 sub {m[1]}"
    return n

def check_neutral_c3(vecs):
    """C3 E.4 A16 / `FIRMWARE.md` §7.13: the v1–v4 sets answer alike on a bit-11 build (their `features` lists carry
    0xdff) only while none of them sends what bit 11 gives a meaning at any depth — mode 26 sub 8, mode 27 sub 4, flag
    bit 6 (a version-2 record and kind 3 stay refused as before while EFFECTS is clear; 27/3, 26/6, 26/7 stay unknown)."""
    n = 0
    for vec in vecs:
        for si, st in enumerate(vec["steps"]):
            for op in st["ops"]:
                where = f"{vec['name']} step {si}"
                assert not ("flags" in op and op["flags"] & 0x0040), f"{where}: a FLAGS_SET with bit 6"
                if "msg" not in op: continue
                for m in walk_msgs(bytes.fromhex(op["msg"])):
                    n += 1
                    assert not (len(m) >= 2 and m[0] & 0x7F == 26 and m[1] == 8), f"{where}: mode-26 sub 8"
                    assert not (len(m) >= 2 and m[0] & 0x7F == 27 and m[1] == 4), f"{where}: mode-27 sub 4"
    return n

def check_v3(vecs, c2=False, c3=False):
    """Raise AssertionError on the first message, op or program that does not decode as built (C1's set, or with
    `c2` C2's: modes 26 subs 3..5, 27 and 28, the ops `input` and `page`, the programs SET and COMMIT name; with `c3`
    C3's too: mode 26 sub 8 (17 B), mode 27 sub 4 (13 B), version-2 records walked by their 8-B header and 40-B
    segments, and what each GLYPHS segment of a program built well formed names — `walk_glyphs`)."""
    c2 = c2 or c3
    LEN26 = {0: 18, 1: 11, 2: 3, **({3: 18, 4: 8, 5: 10} if c2 else {}), **({8: 17} if c3 else {})}
    LEN27 = {0: 23, 1: 3, 2: 12, **({4: 13} if c3 else {})}   # §6.3, §7.8, the two header bytes included
    n_msgs = n_progs = n_glyphs = 0
    found = set()                                   # the records a PLAY, SET or COMMIT found at its prog offset
    for vec in vecs:
        cache = bytearray(262144)
        def subs(m):
            if m[0] == 8 and len(m) >= 2:
                p, out = 2, []
                for _ in range(m[1]):
                    ln = m[p] | m[p + 1] << 8; out.append(m[p + 2:p + 2 + ln]); p += 2 + ln
                assert p == len(m), f"{vec['name']}: a batch's sub-messages do not end at its end"
                return out
            if m[0] == 16 and len(m) > 2 and m[1] == 1: return [m[2:]]
            return [m]
        def named(off4):                            # the program a message names: recorded, and walked when built well formed
            nonlocal n_progs, n_glyphs
            off = off4 * 4
            ln = cache[off + 2] | cache[off + 3] << 8 if off + 4 <= len(cache) else 0
            rec = bytes(cache[off:off + ln])
            found.add(rec)
            if rec in WELL_FORMED:
                assert walk_prog(cache, off) == len(rec); n_progs += 1
                if c3 and rec[0] == 2: n_glyphs += walk_glyphs(cache, off)
        if c3:
            assert vec["features"] == [FEATURES_C3] and vec["panel"] is True, f"{vec['name']}: features or panel"
        elif c2:
            assert vec["features"] == [FEATURES_C2, FEATURES_C3] and vec["panel"] is True, f"{vec['name']}: features or panel"
            assert any("page" in op for op in vec["steps"][0]["ops"] + vec["steps"][1]["ops"]) or \
                not any("input" in op for st in vec["steps"] for op in st["ops"]), f"{vec['name']}: no page op at its start"
        last = None
        for si, st in enumerate(vec["steps"]):
            clock_set = False
            where = f"{vec['name']} step {si}"
            assert st["expect"] == {}, f"{where}: an expectation written by this script"
            for op in st["ops"]:
                if "tick" in op:                    # §5.10: never backward, nor forward by 2^31 or more (mod 2^32)
                    assert last is None or (op["tick"] - last) & M32 < 1 << 31, f"{where}: the clock goes back to {op['tick']}"
                    last, clock_set = op["tick"], True
                    continue
                if "wake" in op:
                    assert clock_set, f"{where}: a wake with no tick op before it in its step"
                    continue
                if "input" in op:                   # §6.10: [devType, eventId, value] after a tick; "only": "L"
                    assert c2 and clock_set, f"{where}: an input with no tick op before it in its step"
                    d, e, val = op["input"]
                    assert 0 <= d <= 0xFFFF and 0 <= e <= M32 and 0 <= val <= M32 and op.keys() <= {"input", "only"}
                    assert op.get("only", "L") == "L", f"{where}: only {op.get('only')}"
                    n_msgs += 1
                    continue
                if "page" in op:
                    assert c2 and isinstance(op["page"], bool) and len(op) == 1, f"{where}: page {op}"
                    continue
                if op.get("lease") == "release": cache[:] = bytes(len(cache)); continue
                if "msg" not in op: continue
                for m in subs(bytes.fromhex(op["msg"])):
                    if not m: continue
                    mode = m[0] & 0x7F
                    if m in BAD_SHAPE: continue
                    if mode in (27, 28): assert c2, f"{where}: mode {mode} in a C1 vector"
                    if mode == 27:                  # §6.3
                        n_msgs += 1
                        assert clock_set, f"{where}: mode 27 with no tick op before it in its step"
                        assert len(m) >= 2 and m[1] in LEN27 and len(m) == LEN27[m[1]], f"{where}: mode 27 of {len(m)} B"
                        if m[1] == 0 and (m[9] | m[10] << 8) != NONE: named(m[9] | m[10] << 8)
                    elif mode == 28:                # §6.6
                        n_msgs += 1
                        assert len(m) == (12 if m[0] & 0x80 else 10), f"{where}: mode {m[0]:#x} of {len(m)} B"
                    elif mode == 26:
                        n_msgs += 1
                        assert clock_set, f"{where}: an engine message with no tick op before it in its step"
                        assert len(m) >= 2 and m[1] in LEN26 and len(m) == LEN26[m[1]], f"{where}: mode 26 of {len(m)} B"
                        if m[1] in (0, 8): named(m[4] | m[5] << 8)
                        if m[1] == 3: named(m[8] | m[9] << 8)
                    elif mode in (19, 25):
                        if mode == 25:
                            n_msgs += 1
                            body = zlib.decompress(m[1:])
                            assert len(body) <= 16384 or vec["name"] == "v3-zwrite", f"{vec['name']}: mode 25 inflates past 16 KiB"
                        else:
                            body = m[1:]
                        for off4, data in entries19(body):
                            if off4 * 4 + len(data) <= len(cache): cache[off4 * 4:off4 * 4 + len(data)] = data
                    elif mode == 12:
                        for off, data in [(o, d) for o, d in entries19(m[1:])]:
                            cache[off:off + len(data)] = data
                    elif mode == 11:
                        cache[:] = bytes(len(cache))
    return n_msgs, n_progs, found, n_glyphs

def check_found(found):
    """Every program built well formed walks, and every program built (well formed or not) was found by a PLAY, SET or
    COMMIT at the offset it names: an offset the builders got wrong reads some other bytes and fails here."""
    for rec in WELL_FORMED:
        assert walk_prog(bytearray(rec), 0) == len(rec)
    progs_bad = {r for r in BAD_SHAPE if len(r) >= 4 and r[0] in (1, 2) and (r[2] | r[3] << 8) == len(r)}
    missed = (WELL_FORMED | progs_bad) - found
    assert not missed, f"{len(missed)} program(s) built but never found where a PLAY, SET or COMMIT names them"

def check_pairs(vecs):
    """Spec §3 DV: a "pair" key names exactly two vectors that send the same messages and differ only in their wakes
    (ticks and wake ops), so both harnesses can compare their final P."""
    pairs = {}
    for vec in vecs:
        if "pair" in vec: pairs.setdefault(vec["pair"], []).append(vec)
    for name, vs in pairs.items():
        assert len(vs) == 2, f"pair {name}: {len(vs)} vectors"
        msgs = lambda v: [op for st in v["steps"] for op in st["ops"] if "msg" in op or "flags" in op or "lease" in op]
        assert msgs(vs[0]) == msgs(vs[1]), f"pair {name}: the messages differ"
        assert vs[0]["steps"][-1]["ops"] != vs[1]["steps"][-1]["ops"] or len(vs[0]["steps"]) != len(vs[1]["steps"]), name
    return len(pairs)

def _merged(p, vec):
    """The vector to write at `p`: its inputs, and the expectations the file already holds for the longest prefix of
    steps whose inputs (and every vector-level key) are unchanged — the C's own answers for the same input, never
    an expectation of ours. From the first changed step on, every expectation is empty, for the C to fill."""
    if not p.exists(): return vec
    old = json.loads(p.read_text())
    meta = lambda d: {k: x for k, x in d.items() if k not in ("steps", "features")}
    feats = lambda d: set(d["features"]) if isinstance(d.get("features"), list) else {d.get("features")}
    # `features` widened to a list holding the old value (C2 B.2) keeps the expectations: they are the C's answers on
    # a build the vector is still for; any other change of a vector-level key empties them all
    if meta(old) != meta(vec) or not feats(old) <= feats(vec): return vec
    for new_st, old_st in zip(vec["steps"], old["steps"]):
        if new_st["ops"] != old_st["ops"]: break
        new_st["expect"] = old_st.get("expect", {})
    return vec

def main():
    import argparse
    ap = argparse.ArgumentParser(description="Write the conformance vectors' inputs (FIRMWARE.md §9, §5.10).")
    ap.add_argument("--only", metavar="PREFIXES", help="write only vectors whose name starts with one of these "
                    "comma-separated prefixes (e.g. v3 or v2-cachesize,v2-refusals); every other file is left as it is")
    ap.add_argument("--check", action="store_true", help="run the self-check of the C1 builders and write nothing")
    ap.add_argument("--out", metavar="DIR", help="write into DIR instead of firmware/vectors/ (inputs no runner has "
                    "filled yet, kept outside the tree: an unfilled file there turns ConformanceVectorTest red)")
    a = ap.parse_args()
    kf = keyframe(pattern(W, H, 1))
    v12, v3, v4, v5 = vectors(), vectors_v3(kf), vectors_v4(kf), vectors_v5(kf)
    v5x = vectors_v5_x2(kf)                          # stage X2D: new files only (`v5-x2-*`); the 86 above stay as they are
    v5x += vectors_v5_x3(kf)                         # stage X3D: one new file (`v5-x3-hint`); `v5-ship-*` are ShipVectors' (Kotlin)
    v5x += vectors_v5_dw(kf)                         # C3 stage DW-3: one new file (`v5-dw-fire`); `v5-ship-wheel-*` are ShipVectors'
    v5x += vectors_v5_dm(kf)                         # C3 stage DM-2a: one new file (`v5-dm-rain-fire`); `v5-ship-ring-matrix-352` is ShipVectors'
    v5x += vectors_v5_dm2b(kf)                       # C3 stage DM-2b: one new file (`v5-dm-cells-fire`); `v5-ship-ring-scramble-352` is ShipVectors'
    n_msgs, n_progs, found3, _ = check_v3(v3)
    m4, p4, found4, _ = check_v3(v4, c2=True)
    m5, p5, found5, g5 = check_v3(v5 + v5x, c3=True)
    n_pairs = check_pairs(v5)
    check_found(found3 | found4 | found5)
    n_neutral = check_neutral(v12 + v3)
    n_neutral3 = check_neutral_c3(v12 + v3 + v4)
    for build, got in ((vectors_v3, v3), (vectors_v4, v4), (vectors_v5, v5), (lambda k: vectors_v5_x2(k) + vectors_v5_x3(k) + vectors_v5_dw(k) + vectors_v5_dm(k) + vectors_v5_dm2b(k), v5x)):
        assert json.dumps(build(kf)) == json.dumps(got), f"{build.__name__} is not deterministic"
    print(f"self-check: {len(v3)} C1 vectors, {n_msgs} mode-25/26 messages decoded, {n_progs} named programs walked; "
          f"{len(v4)} C2 vectors, {m4} mode-26/27/28 messages and inputs decoded, {p4} named programs walked; "
          f"{len(v5)} + {len(v5x)} C3 vectors ({n_pairs} fold pairs), {m5} mode-26/27/28 messages and inputs decoded, {p5} named programs "
          f"walked, {g5} GLYPHS segments' tables, lists, alphabets, rain records and glyphs read; "
          f"{n_neutral} v1/v2/v3 messages build-neutral; {n_neutral3} v1–v4 messages neutral on a bit-11 build; "
          f"{len(WELL_FORMED)} programs built well formed, "
          f"{len(BAD_SHAPE)} malformed on purpose")
    if a.check: return
    prefixes = tuple(x for x in a.only.split(",") if x) if a.only else None
    out = pathlib.Path(a.out) if a.out else OUT
    out.mkdir(parents=True, exist_ok=True)
    for vec in v12 + v3 + v4 + v5 + v5x:
        if prefixes and not vec["name"].startswith(prefixes): continue
        p = out / f"{vec['name']}.json"
        vec = _merged(p, vec)
        p.write_text(json.dumps(vec, indent=1) + "\n")
        kept = sum(1 for s in vec["steps"] if s["expect"])
        print(f"wrote {p.name}: {len(vec['steps'])} steps ({kept} expectations kept), {p.stat().st_size:,} B")

if __name__ == "__main__":
    main()
