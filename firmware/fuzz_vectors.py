#!/usr/bin/env python3
"""Differential fuzz for the drawing contract: random message sequences as conformance vectors.

Context: two implementations follow `FIRMWARE.md` independently — the fork's C
(`~/damage-cfw`) and Damage's Kotlin simulator. The vectors in `firmware/vectors/` prove they
agree on what someone thought to write down; this writes sequences nobody thought of. It found
the one class they disagreed on (`HANDOFF.md` §64.2 item 1: v1 does not roll back a mode-3/6
stream refusal), and it confirmed agreement everywhere else — which is worth having too.

Clean-room Damage code, as `make_vectors.py` is: every message is built from the documented
formats through that file's builders, never from the firmware C.

    python3 firmware/fuzz_vectors.py OUT_DIR 40 25            # 40 vectors, 25 random steps each
    python3 firmware/fuzz_vectors.py OUT_DIR 40 25 --seed 1000 --corrupt
    python3 firmware/fuzz_vectors.py OUT_DIR 40 25 --c1       # C1's set: modes 25 and 26 too (`FIRMWARE.md` §5)

    (cd ~/damage-cfw && python3 host/run_vectors.py --dir OUT_DIR --write)   # the C answers
    DAMAGE_VECTOR_DIR=OUT_DIR ./gradlew :core:cleanTest :core:test \
        --tests '*ConformanceVectorTest*'                                    # the simulator agrees?

`--corrupt` truncates or flips a byte in one message in eight. **It has one KNOWN false
positive**, so read a corrupted-set disagreement before believing it: a mode-3/6 stream whose
data inflates completely and fails only its trailing adler check makes the C write the whole box
(zlib hands back the bytes and the error together) where `Zl.inflateRleInto` writes nothing for
that last call — `java.util.zip.Inflater` returns either the bytes or the exception, never both,
and the limit is stated on that function. Recognise it by decompressing the step's message: an
"incorrect data check" from `zlib` is this case. The shapes that are EXACT on both sides, and the
ones a defect on our own side produces, are a truncated stream and an RLE that decodes short of
or past its rect — `v1-stream` pins all three as a permanent vector.

A disagreement is a finding about the simulator or about the docs, never a reason to edit an
expectation (`FIRMWARE.md` §9). Write the reduced case up as a vector in `make_vectors.py`.

`--c1` (build C1, spec §5) adds, among the same messages: programs from random tracks (1..8) and segments
(1..8, every verb and ease, kinds 0/2/3, random pref words) with some wild areas (off the panel, empty, a pair of
two sizes); PLAY / RETARGET / CLOSE with random slots (0..4, 0xFF), periods (15..129), P values (s16 edges) and
`cap` (inside the reserved region, past the cache, over the program); cache writes (12, 19, 25) over the
programs' and records' bytes; mode 25 near its 16,384-byte limit; `{"wake": true}` at step boundaries and
between them; mode 11, the lease, `{"stock": true}`. Every engine message and every wake follows a `tick` op in
its step; every vector carries a wake, so none has a self-test form, and "features": 0x1ff. Without `--c1` the
generator is unchanged: a seed writes the vector it wrote before.
"""
import argparse, json, pathlib, random, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from make_vectors import (  # noqa: E402
    W, H, u16, rect, rle, nibbles_packed, pattern,
    keyframe, delta, delta_stereo, copy, copy_stereo, batch,
    cache_image, cache_write, draw_image, draw_string,
    image2, draw2, draw2_pair, string2, string2_pair, cache_write2,
    clip, clip_pair, fill, fill_pair, lut, lut_pair,
    capture, capture_pair, restore, free_slot, hint, step, msg, Cache2,
    seg, track, prog, easing, play, retarget, close, zwrite, wake,
    ENGINE, ZWRITE, FEATURES_C1,
)

DRAW2, PROBE = 0x0004, 0x8000
CORRUPT = False
C1 = False
def rnd_rect(r, wild=False):
    if wild and r.random() < 0.35:
        l = r.choice([0, 1, 639, 640, 641, 65535])
        t = r.choice([0, 1, 479, 480, 481, 65535])
        w = r.choice([0, 1, 2, 640, 641])
        h = r.choice([0, 1, 2, 480, 481])
        return rect(l, t, w, h)
    l = r.randrange(0, 640); t = r.randrange(0, 480)
    w = r.randrange(1, 640 - l + 1); h = r.randrange(1, 480 - t + 1)
    return rect(l, t, w, h)

def rnd_levels(r, n):
    out = []
    while len(out) < n:
        if r.random() < 0.5: out.extend([r.randrange(16)] * r.randrange(1, 40))
        else: out.extend(r.randrange(16) for _ in range(r.randrange(1, 10)))
    return out[:n]

def build_cache(r):
    """A v2 cache image: some records, a 224-entry font table, some glyphs."""
    c = Cache2(start=64)
    recs, glyphs = [], []
    for _ in range(r.randrange(1, 4)):
        w = r.randrange(1, 60); h = r.randrange(1, 40)
        recs.append(c.add(image2(w, h, rnd_levels(r, w * h))))
    for _ in range(8):
        gw = r.randrange(1, 20); gh = r.randrange(1, 20)
        glyphs.append(c.add(bytes([gw, gh]) + rle(rnd_levels(r, gw * gh))))
    table_off4 = c.reserve(448)
    table = bytearray()
    for i in range(224):
        table += u16(glyphs[i % len(glyphs)])
    c.put(table_off4, bytes(table))
    return c, recs, table_off4

def corrupt(r, b):
    b = bytearray(b)
    k = r.random()
    if k < 0.4 and len(b) > 1: return bytes(b[:r.randrange(1, len(b))])        # truncate
    if k < 0.7 and b: b[r.randrange(len(b))] ^= 1 << r.randrange(8); return bytes(b)
    return bytes(b) + bytes(r.randrange(1, 8))                                  # trailing junk

def gen(seed, nsteps):
    if C1: return gen_c1(seed, nsteps)
    r = random.Random(seed)
    c, recs, table_off4 = build_cache(r)
    steps = [step({"tick": 1000}, {"lease": "acquire"},
                  {"cachesize": r.choice([64, 96, 160])},
                  {"flags": DRAW2 | (PROBE if r.random() < 0.3 else 0)})]
    for m in c.messages(chunk=2500):
        steps.append(step(msg(m)))
    steps.append(step(msg(keyframe(pattern(W, H, seed)))))
    now = 1000
    fid = 1
    for _ in range(nsteps):
        ops = []
        for _ in range(r.randrange(1, 3)):
            k = r.random()
            if k < 0.05:
                # inside the lease's 90,000 ticks, deliberately: the self-test FORM acquires once
                # and holds it (`FIRMWARE.md` §9), so a vector that ticks past the deadline has no
                # faithful form and every step after it reads as "no lease" there while the normal
                # path carries on. The normal path itself is happy with any clock; it is the form
                # that cannot follow. A vector that means to test the lapse is written by hand
                # (`v1-lease`), where the runners skip it knowingly.
                now += r.randrange(1, 20_000); ops.append({"tick": now}); continue
            if k < 0.08:
                ops.append({"lease": r.choice(["acquire", "release"])}); continue
            if k < 0.11:
                ops.append({"cachesize": r.choice([32, 63, 64, 100, 160, 161, 300])}); continue
            if k < 0.14:
                ops.append({"flags": r.choice([0, DRAW2, PROBE, DRAW2 | PROBE, 0xFFFF])}); continue
            m = rnd_msg(r, recs, table_off4, fid)
            if any(x[0] == 3 for x in [m]): pass
            if m[:1] == b"\x03" or m[:1] == b"\x83": fid += 1
            if CORRUPT and r.random() < 0.12: m = corrupt(r, m)
            ops.append(msg(m))
        steps.append(step(*ops))
    return {"name": f"fuzz-{seed:04d}", "contract": 2, "panel": True, "steps": steps}

def rnd_msg(r, recs, table_off4, fid, depth=0, cc=None):
    if cc is not None and r.random() < 0.4:           # --c1: two in five messages are C1's (a PLAY the likeliest)
        return rnd_c1(r, r.choice([22, 22, 23, 24, 25]), cc, depth)
    k = r.randrange(22)
    wild = r.random() < 0.3
    if k == 0:   # mode 3 delta (aligned)
        l = r.randrange(0, 160) * 4; t = r.randrange(0, 240) * 2
        w = r.randrange(1, (640 - l) // 4 + 1) * 4; h = r.randrange(1, (480 - t) // 2 + 1) * 2
        return delta(l, t, w, h, fid, rnd_levels(r, w * h))
    if k == 1:   # mode 3 stereo
        w = r.randrange(1, 40) * 4; h = r.randrange(1, 40) * 2
        lL = r.randrange(0, (640 - w) // 4 + 1) * 4; lR = r.randrange(0, (640 - w) // 4 + 1) * 4
        t = r.randrange(0, (480 - h) // 2 + 1) * 2
        return delta_stereo(lL, lR, t, w, h, fid, rnd_levels(r, w * h))
    if k == 2:   # mode 6 keyframe
        return keyframe(rnd_levels(r, W * H))
    if k == 3:   # mode 9 copy
        sw = r.randrange(1, 300); sh = r.randrange(1, 200)
        sx = r.randrange(0, 640 - sw); sy = r.randrange(0, 480 - sh)
        dx = r.randrange(0, 640 - sw); dy = r.randrange(0, 480 - sh)
        return copy((sx, sy, sw, sh), (dx, dy))
    if k == 4:   # mode 9 stereo
        sw = r.randrange(1, 200); sh = r.randrange(1, 150)
        f = lambda: (r.randrange(0, 640 - sw), r.randrange(0, 480 - sh))
        return copy_stereo((*f(), sw, sh), f(), (*f(), sw, sh), f())
    if k == 5:   # mode 13 v1 draw
        return draw_image(r.randrange(0, 0x4000), r.randrange(0, 700), r.randrange(0, 520), r.randrange(256))
    if k == 6:   # mode 14 v1 string
        n = r.randrange(0, 12)
        return draw_string(r.randrange(0, 0x4000), r.randrange(0, 700), r.randrange(0, 520),
                           r.randrange(256), bytes(r.randrange(1, 130) for _ in range(n)))
    if k == 7:   # mode 12 v1 cache write
        n = r.randrange(1, 3)
        return cache_write(*[(r.randrange(0, 0x10000), bytes(r.randrange(256) for _ in range(r.randrange(1, 40)))) for _ in range(n)])
    if k == 8:   # mode 17 v2 draw
        return draw2(r.choice(recs), r.randrange(-100, 700), r.randrange(-100, 520), r.randrange(256))
    if k == 9:   # mode 17 per-lens
        return draw2_pair(r.choice(recs), r.randrange(-100, 700), r.randrange(-100, 700),
                          r.randrange(-100, 520), r.randrange(256))
    if k == 10:  # mode 18 v2 string
        n = r.randrange(0, 14)
        return string2(table_off4, r.randrange(-50, 700), r.randrange(-50, 520), r.randrange(256),
                       bytes(r.choice([r.randrange(1, 32), r.randrange(32, 256), 0]) for _ in range(n)))
    if k == 11:  # mode 18 per-lens
        n = r.randrange(0, 10)
        return string2_pair(table_off4, r.randrange(-50, 700), r.randrange(-50, 700),
                            r.randrange(-50, 520), r.randrange(256),
                            bytes(r.randrange(1, 256) for _ in range(n)))
    if k == 12:  # mode 19 v2 cache write
        n = r.randrange(0, 3)
        ent = []
        for _ in range(n):
            ln = r.randrange(0, 60)
            ent.append((r.randrange(0, 0x9000), bytes(r.randrange(256) for _ in range(ln))))
        return cache_write2(*ent)
    if k == 13:  return clip(rnd_rect(r, wild))
    if k == 14:  return clip_pair(rnd_rect(r, wild), rnd_rect(r, wild))
    if k == 15:  return fill(rnd_rect(r, wild), r.randrange(0, 18))
    if k == 16:  return fill_pair(rnd_rect(r, wild), rnd_rect(r, wild), r.randrange(0, 18))
    if k == 17:  return lut(rnd_rect(r, wild), [r.randrange(16) for _ in range(16)])
    if k == 18:  return lut_pair(rnd_rect(r, wild), rnd_rect(r, wild), [r.randrange(16) for _ in range(16)])
    if k == 19:  # mode 23 save-under
        s = r.randrange(0, 6)
        sub = r.random()
        if sub < 0.45: return capture(s, rnd_rect(r, wild)) if r.random() < 0.7 else capture_pair(s, rnd_rect(r, wild), rnd_rect(r, wild))
        if sub < 0.8: return restore(s)
        return free_slot(s)
    if k == 20:  # mode 24 hint (only meaningful in a batch)
        return hint(r.randrange(0, 520), r.randrange(0, 520))
    # k == 21: a batch
    if depth >= 1: return fill(rnd_rect(r, False), r.randrange(16))
    subs = [rnd_msg(r, recs, table_off4, fid + i, depth + 1, cc) for i in range(r.randrange(1, 5))]
    return batch(*subs)

# ---- --c1: modes 25 and 26 (`FIRMWARE.md` §5.4, §5.5, §5.9), built through make_vectors' C1 builders ---------------
S16_EDGES = [-32768, -1, 0, 1, 32767]

def rnd_pref(r, tame=False):
    if r.random() < (0.85 if tame else 0.6): return 0
    field = r.randrange(14) if r.random() < 0.95 else r.randrange(14, 32)
    return 0x80 | (field << 2) | r.randrange(4)

def rnd_seg(r, recs, tables, tame, pair):
    verb = r.randrange(4)
    steps = r.randrange(1, 9) if tame else r.choice([1, 2, r.randrange(1, 13), r.randrange(1, 40), 255, r.randrange(256)])
    ease = r.choice([0, 1, 2]) if tame else r.choice([0, 1, 2, 2, r.randrange(256)])
    table = r.choice(tables) if (ease == 2 and r.random() < 0.85) else r.randrange(0x10000)
    if ease != 2 and tame: table = 0
    kind = src = 0
    if verb == 1: kind = r.randrange(16) if tame else r.randrange(18)
    elif verb == 2: kind = r.choice([0, 0, 2]) if tame else r.choice([0, 2, 1, r.randrange(256)])
    elif verb == 3: kind = r.choice([0, 2, 3]) if tame else r.choice([0, 2, 3, 1])
    if verb in (2, 3) and kind == 0: src = r.choice(recs) if r.random() < 0.9 else r.randrange(0x10000)
    if verb == 3 and kind == 3: src = r.randrange(16) if tame else r.randrange(18)
    xy = lambda: r.randrange(-100, 700)
    d = lambda: r.randrange(-300, 301)
    lv = lambda: r.randrange(16) if tame else r.randrange(17)
    opt = 0
    if verb == 2: opt = r.choice([0, 0x10, 0x20, 0x30]) if tame else r.choice([0, 0x10, 0x30, r.randrange(256)])
    if verb == 3: opt = r.randrange(8) if tame else r.randrange(256)
    odd = (lambda: 0) if tame else (lambda: 1 if r.random() < 0.05 else 0)
    if verb == 0:
        return seg(0, steps, pref0=rnd_pref(r) if not tame else 0)
    lo = 1 if tame else 0
    w = r.randrange(lo, 80) if (verb == 3 and kind == 3) else 0
    h = r.randrange(lo, 60) if (verb == 3 and kind == 3) else 0
    xL, xR, y = xy(), xy(), xy()
    dxL, dxR, dy = (d(), d(), d()) if verb == 2 else (w, 0, h)
    if tame and not pair: xR = dxR = 0                # a track with one rect uses xL and dxL on both lenses
    return seg(verb, steps, ease, kind, src, xL, xR, y, dxL, dxR, dy, lv(), lv(), opt, table, odd(), odd(), odd(),
               rnd_pref(r, tame), rnd_pref(r, tame), odd(), odd())

def rnd_area(r, pair, tame):
    if not tame and r.random() < 0.3:
        a = rnd_rect(r, wild=True)
        return (a, rnd_rect(r, wild=True)) if pair else a
    w = r.randrange(1, 200); h = r.randrange(1, 120)
    l = r.randrange(0, 640 - w + 1); t = r.randrange(0, 480 - h + 1)
    if not pair: return rect(l, t, w, h)
    l2 = min(639 - w + 1, max(0, l + r.randrange(-24, 25)))
    if not tame and r.random() < 0.15: return (rect(l, t, w, h), rect(l2, t, w + 1 if w < 640 else w - 1, h))   # two sizes
    return (rect(l, t, w, h), rect(l2, t, w, h))

def rnd_prog(r, recs, tables, tame):
    tracks = []
    for i in range(r.randrange(1, 4) if tame else r.randrange(1, 9)):
        pair = r.random() < 0.3
        bd = r.choice([r.randrange(16), r.randrange(16), 0x20]) if tame else \
            r.choice([r.randrange(16), 0x20, 0xFF, r.randrange(256)])
        segs = [rnd_seg(r, recs, tables, tame, pair) for _ in range(r.randrange(1, 3) if tame else r.randrange(1, 9))]
        tracks.append(track(rnd_area(r, pair, tame), bd, *segs))
    return prog(*tracks)

def build_cache_c1(r):
    """The v1/v2 cache of `build_cache`, then easing tables, programs and a region reserved for captures."""
    c, recs, table_off4 = build_cache(r)
    tables = []
    for _ in range(r.randrange(1, 4)):
        n = r.randrange(1, 17)
        E = [r.randrange(-1000, 6000) for _ in range(n - 1)] + [4096 if r.random() < 0.85 else r.randrange(5000)]
        tables.append(c.add(easing(E)))
    progs = []
    for _ in range(r.randrange(2, 6)):
        rec = rnd_prog(r, recs, tables, tame=r.random() < 0.6)
        progs.append((c.add(rec), rec))
    capreg = c.reserve(r.randrange(64, 8192))
    return c, recs, table_off4, {"recs": recs, "tables": tables, "progs": progs, "cap": capreg,
                                 "rec_bytes": {o4: d for o4, d in c.entries}}

def rnd_c1(r, k, cc, depth):
    progs = cc["progs"]
    slot = lambda: r.choice([0, 1, 2, 3, 0, 1, 2, 3, 4, 0xFF])
    pv = lambda: r.choice(S16_EDGES + [r.randrange(-200, 700), r.randrange(-200, 700), r.choice(cc["recs"])])
    if k == 22:                                       # PLAY
        p4, rec = r.choice(progs) if r.random() < 0.9 else (r.randrange(0x10000), b"")
        cap = r.choice([cc["cap"], cc["cap"], cc["cap"] + r.randrange(0, 64), 0xFFF0, p4, r.randrange(0x10000)])
        period = r.choice([15, 16, 34, 34, 50, 128, 129, r.randrange(15, 130)])
        return play(slot(), period, p4, r.randrange(0x10000), cap, [pv() for _ in range(4)])
    if k == 23:                                       # RETARGET or CLOSE
        if r.random() < 0.5: return retarget(slot(), [pv() for _ in range(4)])
        return close(slot())
    if k == 24:                                       # mode 25 near its limit, or a small one
        if depth: return zwrite((cc["cap"], bytes(r.randrange(256) for _ in range(r.randrange(0, 40)))))
        if r.random() < 0.5:
            total = 16384 + r.choice([0, 0, -1, -4, -8, 1, 4])
            data = bytes((i * 13 + r.randrange(3)) & 0xFF for i in range(total - 4))
            return zwrite((0x2000, data))             # byte 32,768: past build_cache's content in any cache size
        return zwrite(*[(o4, d) for o4, d in r.sample(sorted(cc["rec_bytes"].items()), min(2, len(cc["rec_bytes"])))])
    # k == 25: a cache write over what an open instance reads, or mode 11
    if r.random() < 0.15 and not depth: return bytes([11])
    o4, d = r.choice(sorted(cc["rec_bytes"].items()))
    kind = r.random()
    if kind < 0.5: return cache_write2((o4, d))       # the same bytes: the instance closes, the record stays sound
    if kind < 0.75 and o4 * 4 + len(d) <= 65536: return cache_write((o4 * 4, d))
    return cache_write2((cc["cap"] + r.randrange(0, 16), bytes(r.randrange(256) for _ in range(r.randrange(1, 24)))))

def is_engine(m):
    """Mode 26 at the top level, inside a batch or inside a self-test step."""
    if not m: return False
    if m[0] & 0x7F == 26: return True
    if m[0] == 16 and len(m) > 2: return is_engine(m[2:])
    if m[0] == 8 and len(m) >= 2:
        p = 2
        for _ in range(m[1]):
            if p + 2 > len(m): return False
            ln = m[p] | m[p + 1] << 8
            if is_engine(m[p + 2:p + 2 + ln]): return True
            p += 2 + ln
    return False

def gen_c1(seed, nsteps):
    r = random.Random(seed)
    c, recs, table_off4, cc = build_cache_c1(r)
    full = DRAW2 | ENGINE | ZWRITE
    armed = full if r.random() < 0.85 else r.choice([DRAW2 | ENGINE, DRAW2 | ZWRITE, ENGINE | ZWRITE])
    prelude = [{"tick": 1000}, {"lease": "acquire"}, {"cachesize": r.choice([64, 96, 160, 256])}, {"flags": armed}]
    writes = [msg(m) for m in c.messages(chunk=2500)]
    kf = msg(keyframe(pattern(W, H, seed)))
    steps = [step(*prelude), *[step(w) for w in writes], step(kf), step({"tick": 1001}, wake())]
    now, fid = 1001, 1
    last = (now, 34)                                  # the last PLAY's tick and period: wakes aim at its steps
    for _ in range(nsteps):
        ops = []
        for _ in range(r.randrange(1, 4)):
            k = r.random()
            if k < 0.03:
                now += r.randrange(1, 20_000); ops.append({"tick": now}); continue
            if k < 0.05:
                ev = r.choice(["acquire", "release"])
                ops.append({"lease": ev})
                if ev == "release" and r.random() < 0.7:          # most sessions come back: a fresh lease, armed, its cache
                    ops += [{"lease": "acquire"}, {"flags": full}, kf, *writes]
                continue
            if k < 0.07:
                ops.append({"cachesize": r.choice([32, 63, 64, 160, 161, 256, 257])}); continue
            if k < 0.10:
                ops.append({"flags": r.choice([0, DRAW2, full, DRAW2 | ENGINE, DRAW2 | ZWRITE, ENGINE, full | PROBE, 0xFFFF])}); continue
            if k < 0.12:
                ops.append({"stock": True}); continue
            if k < 0.32:                              # a wake on a step boundary of the last PLAY, or between two
                t0, per = last
                j = r.randrange(1, 12)
                due = t0 + j * per + (0 if r.random() < 0.6 else r.randrange(1, per))
                now = max(now, due); ops += [{"tick": now}, wake()]; continue
            m = rnd_msg(r, recs, table_off4, fid, cc=cc)
            if m[:1] == b"\x03" or m[:1] == b"\x83": fid += 1
            if m == bytes([11]) and r.random() < 0.7:
                extra = [{"lease": "acquire"}, {"flags": full}, kf, *writes]
            else:
                extra = []
            if CORRUPT and r.random() < 0.12: m = corrupt(r, m)
            if is_engine(m):
                now += r.choice([0, 1, r.randrange(1, 60)])
                ops.append({"tick": now})
                if m[0] & 0x7F == 26 and len(m) > 4 and m[1] == 0: last = (now, max(1, m[3]))
            ops.append(msg(m))
            ops += extra
        steps.append(step(*ops))
    return {"name": f"fuzzc1-{seed:04d}", "contract": 2, "panel": True, "features": FEATURES_C1, "steps": steps}

def main():
    global CORRUPT, C1
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("out", help="directory to write the vectors into")
    ap.add_argument("count", type=int, help="how many vectors")
    ap.add_argument("steps", type=int, help="random steps per vector (a prelude and a keyframe precede them)")
    ap.add_argument("--seed", type=int, default=1, help="the first vector's seed; each one after it is seed+i")
    ap.add_argument("--corrupt", action="store_true", help="truncate or flip a byte in one message in eight")
    ap.add_argument("--c1", action="store_true", help="C1's set: modes 25 and 26, wakes, mode 11 (FIRMWARE.md §5)")
    a = ap.parse_args()
    CORRUPT = a.corrupt
    C1 = a.c1
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    for i in range(a.count):
        v = gen(a.seed + i, a.steps)
        (out / f"{v['name']}.json").write_text(json.dumps(v, indent=1) + "\n")
    print(f"wrote {a.count} vector(s) of {a.steps} random step(s) to {out}"
          + (" (one message in eight corrupted)" if CORRUPT else ""))


if __name__ == "__main__":
    main()
