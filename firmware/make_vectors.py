#!/usr/bin/env python3
"""Write the INPUTS of the v1 conformance vectors (`FIRMWARE.md` §9) into firmware/vectors/.

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

    python3 firmware/make_vectors.py            # rewrites firmware/vectors/v1-*.json (inputs only)

Ops in a step: {"tick": ms} sets the firmware clock, {"lease": "acquire"|"release"}
sends the framebuffer lease op, {"msg": HEX} is one completed image message.
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
