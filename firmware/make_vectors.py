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

def prog(*tracks, version=1, ntracks=None, length=None, tail=b"", bad=False):
    """A program record (§5.4): version 1 · tracks · length u16 (the whole record), then the tracks."""
    body = b"".join(tracks) + tail
    rec = bytes([version, len(tracks) if ntracks is None else ntracks]) + u16(4 + len(body) if length is None else length) + body
    (BAD_SHAPE if bad else WELL_FORMED).add(rec)
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
        vec_["features"] = [FEATURES_C1, FEATURES_C2]                  # C2 B.2: build-neutral, run at both
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
TAP, DOUBLE, LONG, UP, DOWN, PRESS, RELEASE = 0, 1, 3, 4, 5, 0xD, 0xE    # §6.1 stock's event ids (4 UPROLL, 5 DOWNROLL)
TOUCH_L, TOUCH_R, RING, ANY = 0, 1, 4, 0xFF                              # §6.1 devTypes; 0xFF a scroll entry's "any" (§6.3)
NONE = 0xFFFF                              # §6.3 LIST's "none"; a tap entry's prog for the report-only form
K_ARG, K_ITEM, K_PITCH = 0, 1, 2           # §6.2 step 9: arg · the item table at c' + arg · c' × pitch + arg
M32 = 0xFFFFFFFF

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
        bind_set(0, ANY, UP, 0, 1, 0, P, 0x3FFE),                # a program past the cache: 5
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
              bind_set(0, ANY, UP, 0, 1, 0, P, f.srcp, [(K_ARG, 0x3FFE), (K_ARG, 16)]),   # S 8: a record past the cache (and level 16): 5 before 12
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
              vscale(0x3FFE, 0, 0, 0x0F, 0), vscale(r30, 0, 0, 0x0F, 2049),               # H 0 (and a record past the cache): 12 before 5; H 2049: 12
              vscale(0x3FFE, 0, 0, 0x0F, 30),                                             # a record past the cache: 5
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
    st += [step(tk(t), flags(ENGINE | BIND), msg(vscale(0x3FFE, 0, 0, 0x0F, 0)), flags(full)),   # mode 28 with DRAW2 clear: 9 before 12
           step(tk(t + 10), {"lease": "release"}, msg(vscale(0x3FFE, 0, 0, 0x0F, 0)))]          # no lease: 3 before 12
    vec("v4-vscale-draw", st)

    for vec_ in v:
        vec_["contract"] = 2
        vec_["start"] = "zero"
        vec_["panel"] = True
        vec_["features"] = FEATURES_C2
    return v

# ---- the self-check (spec §3 D1): every message decodes by its builder's inverse, every program walks to its
# own length. It proves the encoders' lengths, not the semantics (that is the C's and the simulator's).
def walk_prog(buf, off):
    """Walk a program record at byte `off` (§5.4); return its length, raise ValueError when it does not end there."""
    if off + 4 > len(buf): raise ValueError("header past the cache")
    nt, ln = buf[off + 1], buf[off + 2] | buf[off + 3] << 8
    p = off + 4
    for _ in range(nt):
        if p + 4 > len(buf): raise ValueError("track past the cache")
        ns, pl = buf[p], buf[p + 2]
        p += 4 + (16 if pl else 8) + 32 * ns
    if p - off != ln: raise ValueError(f"walked {p - off} B, the header says {ln}")
    return ln

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

def check_v3(vecs, c2=False):
    """Raise AssertionError on the first message, op or program that does not decode as built (C1's set, or with
    `c2` C2's: modes 26 subs 3..5, 27 and 28, the ops `input` and `page`, the programs SET and COMMIT name)."""
    LEN26 = {0: 18, 1: 11, 2: 3, **({3: 18, 4: 8, 5: 10} if c2 else {})}
    LEN27 = {0: 23, 1: 3, 2: 12}                    # §6.3, the two header bytes included
    n_msgs = n_progs = 0
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
            nonlocal n_progs
            off = off4 * 4
            ln = cache[off + 2] | cache[off + 3] << 8 if off + 4 <= len(cache) else 0
            rec = bytes(cache[off:off + ln])
            found.add(rec)
            if rec in WELL_FORMED:
                assert walk_prog(cache, off) == len(rec); n_progs += 1
        if c2:
            assert vec["features"] == FEATURES_C2 and vec["panel"] is True, f"{vec['name']}: features or panel"
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
                        if m[1] == 0: named(m[4] | m[5] << 8)
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
    return n_msgs, n_progs, found

def check_found(found):
    """Every program built well formed walks, and every program built (well formed or not) was found by a PLAY, SET or
    COMMIT at the offset it names: an offset the builders got wrong reads some other bytes and fails here."""
    for rec in WELL_FORMED:
        assert walk_prog(bytearray(rec), 0) == len(rec)
    progs_bad = {r for r in BAD_SHAPE if len(r) >= 4 and r[0] in (1, 2) and (r[2] | r[3] << 8) == len(r)}
    missed = (WELL_FORMED | progs_bad) - found
    assert not missed, f"{len(missed)} program(s) built but never found where a PLAY, SET or COMMIT names them"

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
    a = ap.parse_args()
    kf = keyframe(pattern(W, H, 1))
    v12, v3, v4 = vectors(), vectors_v3(kf), vectors_v4(kf)
    n_msgs, n_progs, found3 = check_v3(v3)
    m4, p4, found4 = check_v3(v4, c2=True)
    check_found(found3 | found4)
    n_neutral = check_neutral(v12 + v3)
    for build, got in ((vectors_v3, v3), (vectors_v4, v4)):
        assert json.dumps(build(kf)) == json.dumps(got), f"{build.__name__} is not deterministic"
    print(f"self-check: {len(v3)} C1 vectors, {n_msgs} mode-25/26 messages decoded, {n_progs} named programs walked; "
          f"{len(v4)} C2 vectors, {m4} mode-26/27/28 messages and inputs decoded, {p4} named programs walked; "
          f"{n_neutral} v1/v2/v3 messages build-neutral; {len(WELL_FORMED)} programs built well formed, "
          f"{len(BAD_SHAPE)} malformed on purpose")
    if a.check: return
    prefixes = tuple(x for x in a.only.split(",") if x) if a.only else None
    OUT.mkdir(parents=True, exist_ok=True)
    for vec in v12 + v3 + v4:
        if prefixes and not vec["name"].startswith(prefixes): continue
        p = OUT / f"{vec['name']}.json"
        vec = _merged(p, vec)
        p.write_text(json.dumps(vec, indent=1) + "\n")
        kept = sum(1 for s in vec["steps"] if s["expect"])
        print(f"wrote {p.name}: {len(vec['steps'])} steps ({kept} expectations kept), {p.stat().st_size:,} B")

if __name__ == "__main__":
    main()
