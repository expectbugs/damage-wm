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
)

DRAW2, PROBE = 0x0004, 0x8000
CORRUPT = False
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

def rnd_msg(r, recs, table_off4, fid, depth=0):
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
    subs = [rnd_msg(r, recs, table_off4, fid + i, depth + 1) for i in range(r.randrange(1, 5))]
    return batch(*subs)

def main():
    global CORRUPT
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("out", help="directory to write the vectors into")
    ap.add_argument("count", type=int, help="how many vectors")
    ap.add_argument("steps", type=int, help="random steps per vector (a prelude and a keyframe precede them)")
    ap.add_argument("--seed", type=int, default=1, help="the first vector's seed; each one after it is seed+i")
    ap.add_argument("--corrupt", action="store_true", help="truncate or flip a byte in one message in eight")
    a = ap.parse_args()
    CORRUPT = a.corrupt
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    for i in range(a.count):
        v = gen(a.seed + i, a.steps)
        (out / f"{v['name']}.json").write_text(json.dumps(v, indent=1) + "\n")
    print(f"wrote {a.count} vector(s) of {a.steps} random step(s) to {out}"
          + (" (one message in eight corrupted)" if CORRUPT else ""))


if __name__ == "__main__":
    main()
