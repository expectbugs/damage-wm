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
    python3 firmware/fuzz_vectors.py OUT_DIR 40 25 --c2       # C2's set: local input too (`FIRMWARE.md` §6)
    python3 firmware/fuzz_vectors.py OUT_DIR 40 25 --c3       # C3's set: effects too (`FIRMWARE.md` §7)

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

`--c2` (build C2, spec §5) is `--c1`'s set with BIND armed and a table bound at every session's start, and among the
same messages: mode 27 (SET in both entry shapes and report-only, CLEAR, LIST; valid and invalid; lists 0..4, shifts
past both ends, marks), mode 26's COMMIT / DISCARD / SETTLE (slots 0..4 and 0xFF, S near RIGHT's last fire and across
the 16-bit wrap, cursors in and out of range, ages 0..0xFFFF), mode 28 and DRAWs with h0 / h1 / anchor, `input` ops
(ids 0, 1, 3, 4, 5, 0xD, 0xE; devTypes 0, 1, 4; one in five `"only": "L"`) and `page` switches between messages, each
input after a `tick`; "features": 0x5ff. `--c1` keeps every draw it made: its seeds write the vectors they wrote
before. C2's sets are written under `private/build072/fuzz-c2/`, never into the repository.

`--c3` (build C3, spec §5) is `--c2`'s set with EFFECTS armed (most sessions) and, among the same messages: version-2
programs over every verb and source — DRAW of a record (scaled in both axes, a source window), of `new`; REVEAL of a
level, a record, `new`; FILL; PIECES layer new (grids 1..64, the three models, f0/f1, stagger, seeds, opt bits);
CELLS (cell lists of 1–40 cells, alphabets with and without 32) and RAIN (reveal 0, 1, 2) — OWN and LAND, a first
in-place track (PIECES layer old, SHRINK, RAIN reveal 1 or 2) in shapes that keep §7.2's fold condition (a later
track apart from a moving one, or painting its whole area every step), and one in three programs perturbed into an
invalid shape; PLAY-AFTER into the slot of the last owning PLAY (drawing or done) and into others; LIST-RING and SET's
kind 3 among mode 27, a ring list and kind-3 entries in the start's table; phone frames under owners (the same
drawing messages); wakes at and between step boundaries; "features": 0xdff. Its sets are written under
`private/c3-design/fuzz-c3/` (seeds 1 and 1001, 40 vectors of 25 steps), never into the repository.

`--c3x` (with `--c3`; stage X2D, `C3-decisions.md` Y, C-2) adds the shapes `--c3` never made, every draw behind the
switch so that `--c3` seeds write the vectors they wrote before: programs whose §7.12 costs straddle the cap in twos and
threes (OWN two-axis DRAWs of `new`, or bound two-axis DRAWs of a record, inside halves, thirds and the whole of the
panel, at exactly the cap's share and one unit past it); ease-2 tables for every step count, never falling and falling
once; later tracks with backdrop none (a lone REVEAL in an OWN program, one inside an earlier whole-area track); per-lens
version-2 tracks; prefs 14–17 (and into unused fields) with parameters near the fields' ranges; GLYPHS at their limits (a
512-cell list, a 224-code alphabet, a 1,024-entry cell table, rain of 128 columns and a trail of 32); a source window and
a cell index set by a parameter (also as list programs a SET's kind 2 or 3 drives). Its sets are written under
`private/c3-design/fuzz-c3x/` (seeds 2 and 1002), never into the repository.

`--dw` (with `--c3`; stage DW-3h, `C3-decisions.md` EB) adds the app switcher's shapes `--c3` never made, every draw
behind the switch so that `--c3` seeds write the vectors they wrote before: version-2 records bound on a list (§7.2: a
SET, a fire and a COMMIT may name one that is not OWN) — the wheel's down and up programs, `make_vectors.wheel_prog`'s
seven per-lens tracks over a level-0 backdrop whose DRAWs take their source from kind-1 parameters, over 2..19 steps;
list 2 bound as a ring over an item table of 1–10 cells, with entries 6 (event 4, step +1) and 7 (event 5, step −1)
in the start's table, either behind Main's entries 0 and 1 (the lowest-numbered match fires, §6.2 step 3) or with the
wheel open (entries 1 and 2 not bound, entry 0 the report-only tap on list 2); and among the messages SETs of those
entries, the report-only tap SET for list 2 and back for list 0 or 1 (one entry held by two lists in turn), LIST-RINGs
of list 2 with a new mark, CLEARs of entries 0–2, 6 and 7, the item table rewritten in place (mode 19) and COMMITs of a
wheel program with item parameters. Its sets are written under `private/c3-design/fuzz-dw/` (seeds 3 and 1003),
never into the repository.
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
    drawrec, pref, F_Y, bind_set, bind_clear, bind_list, commit, discard, settle, vscale, vscale_pair,
    BIND, FEATURES_C2, NONE,
    prog2, d2, dnew, r2, pc, sh, gly, w2, f2, glyph, font224, alphabet, cell, cell_list, cell_table, rain_rec,
    play_after, bind_ring, anc, EFFECTS, FEATURES_C3, K_OLD, K_NEW, CELLS, RAIN, BD_NEW, OWN, LAND, K_CUR,
    UNIFORM, RADIAL, RANDOM, F_AUX0, TOP, BOTTOM, LEFT, RIGHT, SLIDE, K_LEVEL,
    TABLE, F_W0, F_W1, F_AUX1, pcost, CAP,
    WHEEL_GEO, wheel_prog, wheel_tables, wheel_cell, K_ITEM, ANY, RING, TAP, UP, DOWN,
)

DRAW2, PROBE = 0x0004, 0x8000
CORRUPT = False
C1 = False
C2 = False
C3 = False
C3X = False
DW = False
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
    if C1 or C2 or C3: return gen_c1(seed, nsteps, c2=C2 or C3, c3=C3)
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
        return rnd_c1(r, r.choice(([22, 22, 23, 24, 25, 26, 26, 27, 27, 28] + ([29, 29, 29, 30] if cc.get("c3") else [])
                                   + ([29, 29, 29, 29] if cc.get("c3x") else []) + ([31, 31, 31] if cc.get("dw") else []))
                                  if cc.get("c2") else [22, 22, 23, 24, 25]), cc, depth)
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

def rnd_seg(r, recs, tables, tame, pair, c2=False):
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
    l0, l1 = lv(), lv()                               # the C1 draws, in C1's order
    h0, h1, an = odd(), odd(), odd()
    p0, p1 = rnd_pref(r, tame), rnd_pref(r, tame)
    q0, q1 = odd(), odd()
    if c2 and verb == 2 and r.random() < 0.35:        # --c2: a scaled DRAW (§6.6), inside and outside the H rule
        if tame:
            h0, h1 = (0, 0) if r.random() < 0.2 else (r.randrange(1, 120), r.randrange(1, 120))
            an = r.randrange(3)
        else:
            hh = lambda: r.choice([0, 1, 2, 30, 2048, 2049, r.randrange(1, 300), r.randrange(0x10000)])
            h0, h1 = hh(), hh()
            an = r.choice([0, 1, 2, 3, r.randrange(256)])
    return seg(verb, steps, ease, kind, src, xL, xR, y, dxL, dxR, dy, l0, l1, opt, table, h0, h1, an, p0, p1, q0, q1)

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

def rnd_prog(r, recs, tables, tame, c2=False):
    tracks = []
    for i in range(r.randrange(1, 4) if tame else r.randrange(1, 9)):
        pair = r.random() < 0.3
        bd = r.choice([r.randrange(16), r.randrange(16), 0x20]) if tame else \
            r.choice([r.randrange(16), 0x20, 0xFF, r.randrange(256)])
        segs = [rnd_seg(r, recs, tables, tame, pair, c2) for _ in range(r.randrange(1, 3) if tame else r.randrange(1, 9))]
        tracks.append(track(rnd_area(r, pair, tame), bd, *segs))
    return prog(*tracks)

def build_cache_c1(r, c2=False):
    """The v1/v2 cache of `build_cache`, then easing tables, programs and a region reserved for captures; with `c2`
    also list programs (a band whose DRAW takes y from P0: what a binding draws, `FIRMWARE.md` §6.2 step 9) and an
    item table of 8 records (§6.3)."""
    c, recs, table_off4 = build_cache(r)
    tables = []
    for _ in range(r.randrange(1, 4)):
        n = r.randrange(1, 17)
        E = [r.randrange(-1000, 6000) for _ in range(n - 1)] + [4096 if r.random() < 0.85 else r.randrange(5000)]
        tables.append(c.add(easing(E)))
    progs = []
    for _ in range(r.randrange(2, 6)):
        rec = rnd_prog(r, recs, tables, tame=r.random() < 0.6, c2=c2)
        progs.append((c.add(rec), rec))
    capreg = c.reserve(r.randrange(64, 8192))
    cc = {"recs": recs, "tables": tables, "progs": progs, "cap": capreg}
    if c2:
        cc["lprogs"] = []
        for _ in range(r.randrange(1, 4)):
            w, h = r.randrange(20, 200), r.randrange(10, 100)
            l, t = r.randrange(0, 640 - w + 1), r.randrange(0, 480 - h + 1)
            rec = prog(track(rect(l, t, w, h), r.randrange(16),
                             drawrec(r.choice([1, 3, 6, r.randrange(1, 12)]), r.choice(recs), l, 0, lvl=(r.randrange(16), 15),
                                     pref0=pref(0, F_Y))))
            cc["lprogs"].append(c.add(rec))
        cc["itab"] = c.add(b"".join(u16(r.choice(recs)) for _ in range(8)))
        cc["c2"], cc["seq"] = True, 0
    cc["rec_bytes"] = {o4: d for o4, d in c.entries}
    return c, recs, table_off4, cc

# ---- --c2: mode 27, mode 26 subs 3..5, mode 28 (`FIRMWARE.md` §6.3, §6.5, §6.6), through make_vectors' C2 builders ------
def rnd_bind(r, cc):
    """A mode-27 message: SET in both shapes and none, CLEAR, LIST — valid and invalid, lists 0..4."""
    lst = r.choice([0, 0, 1, 1, 2, 3, 4])
    k = r.random()
    if k < 0.5:
        shape = r.random()
        if shape < 0.5: dev, ev, stp = 0xFF, r.choice([4, 5]), r.choice([1, -1, 1, -1, 2, -3])     # a scroll entry
        elif shape < 0.75: dev, ev, stp = 4, 0, 0                                                 # a tap entry
        else: dev, ev, stp = r.choice([0, 1, 4, 0xFF, 7]), r.choice([0, 1, 2, 3, 4, 5, 0xD, 0xE]), r.choice([0, 1, -1, 127, -128])
        if ev == 0 and r.random() < 0.4: p4 = NONE                                                # report-only
        elif r.random() < 0.7: p4 = r.choice(cc["lprogs"])
        elif r.random() < 0.8: p4 = r.choice(cc["progs"])[0]
        else: p4 = r.choice([NONE, r.randrange(0x10000)])
        params = [(r.choice([0, 1, 2, 2, 2, 0]) if r.random() < 0.95 else r.choice([3, 255]),
                   r.choice(S16_EDGES + [0, 0, 10, 100, -100, r.randrange(-200, 700)])) for _ in range(r.randrange(0, 5))]
        return bind_set(r.randrange(8) if r.random() < 0.95 else r.choice([8, 0xFF]), dev, ev, lst, stp,
                        r.choice([0, 0, 1, 1, 2, 3]) if r.random() < 0.95 else 4,
                        r.choice([34, 34, 16, 128, r.randrange(16, 129)]) if r.random() < 0.9 else r.choice([0, 15, 129, 255]),
                        p4, params)
    if k < 0.62: return bind_clear(r.choice([0, 1, 2, 3, 7, 0xFF, 0xFF, 8]))
    items = cc["itab"] if r.random() < 0.7 else r.choice([NONE, r.randrange(0x10000)])
    return bind_list(lst, items, r.choice([1, 2, 4, 8, 8, 8, 0, 1024, 1025, r.randrange(1, 9)]),
                     r.choice([0, 0, 1, -1, 3, -3, 8, -8, 2000, -2000, 32767, -32768]),
                     r.choice([10, -10, 0, 34, 20000, -20000]), r.randrange(256))

def rnd_answer(r, cc, slot, pv):
    """COMMIT, DISCARD or SETTLE (§6.5): S near the last fire RIGHT counted and across the 16-bit wrap, cursors in and
    out of range, any age."""
    n = cc["seq"]
    S = ((n + r.choice([-2, -1, 0, 0, 1, 2])) if r.random() < 0.8 else r.choice([n + 0x8000, n - 0x7FFF, r.randrange(0x10000)])) & 0xFFFF
    lst = r.choice([0, 0, 1, 2, 3, 0xFF, 4])
    cur = r.choice([0, 1, 3, 7, r.randrange(10), 1023, 1024, 0xFFFF])
    k = r.random()
    if k < 0.4:
        p4 = r.choice(cc["lprogs"]) if r.random() < 0.7 else r.choice(cc["progs"])[0]
        return commit(slot(), S, lst, cur, p4, [pv() for _ in range(4)])
    if k < 0.7: return discard(slot(), S, lst, cur)
    return settle(slot(), S, lst, cur, r.choice([0, 1, 34, 256, 450, 0xFFFF, r.randrange(0x10000)]))

def rnd_vscale(r, cc):
    """Mode 28 (§6.6), alone or per lens: H at 0, 1, the record's height and past 2048, clipped at every edge."""
    rec = r.choice(cc["recs"]) if r.random() < 0.9 else r.randrange(0x10000)
    Hh = r.choice([0, 1, 2, 7, 30, 64, 480, 2048, 2049, r.randrange(1, 600), r.randrange(0x10000)])
    x, y, opt = r.randrange(-100, 700), r.randrange(-100, 520), r.randrange(256)
    if r.random() < 0.3: return vscale_pair(rec, x, r.randrange(-100, 700), y, opt, Hh)
    return vscale(rec, x, y, opt, Hh)

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
    if k == 26: return rnd_bind(r, cc)                # --c2's three kinds
    if k == 27: return rnd_answer(r, cc, slot, pv)
    if k == 28: return rnd_vscale(r, cc)
    if k == 29: return rnd_play2(r, cc, slot, pv)     # --c3's two kinds
    if k == 30: return rnd_ring(r, cc)
    if k == 31: return rnd_dw(r, cc)                  # --dw's wheel messages
    # k == 25: a cache write over what an open instance reads, or mode 11
    if r.random() < 0.15 and not depth: return bytes([11])
    o4, d = r.choice(sorted(cc["rec_bytes"].items()))
    kind = r.random()
    if kind < 0.5: return cache_write2((o4, d))       # the same bytes: the instance closes, the record stays sound
    if kind < 0.75 and o4 * 4 + len(d) <= 65536: return cache_write((o4 * 4, d))
    return cache_write2((cc["cap"] + r.randrange(0, 16), bytes(r.randrange(256) for _ in range(r.randrange(1, 24)))))

# ---- --c3: version-2 programs, OWN and LAND, PLAY-AFTER, LIST-RING and kind 3 (`FIRMWARE.md` §7.2–§7.8), built through
# make_vectors' C3 builders. Only gen_c1's c3 path reaches them.
def build_c3(r, c, cc):
    """C3's records beside C2's: a font, alphabets (one holding 32), a cell table of 1–3 lists of 1–40 cells, rain
    records of each reveal, a strip for source windows, and version-2 programs (`rnd_prog2`)."""
    gs = [c.add(glyph(w, h, rnd_levels(r, w * h))) for w, h in ((5, 9), (7, 11), (6, 10), (9, 12))]
    fnt = c.add(font224([gs[k % 4] for k in range(224)]))
    alphas = [c.add(alphabet(sorted(r.sample(range(33, 127), r.randrange(1, 12))))) for _ in range(2)]
    alphas.append(c.add(alphabet([32, 65, 66])))
    lists = []
    for _ in range(r.randrange(1, 4)):
        bx0, by0 = r.randrange(0, 560), r.randrange(0, 420)
        cells = [cell(bx0 + r.randrange(-20, 120), by0 + r.randrange(-10, 60), r.randrange(1, 20), r.randrange(1, 24), fnt,
                      r.choice([0, 32, r.randrange(33, 127)]), r.choice([0, 32, r.randrange(33, 127)]),
                      r.choice([0xFF, r.randrange(16)]), r.choice([15, 8, 0x2F, r.randrange(16)])) for _ in range(r.randrange(1, 41))]
        lists.append(c.add(cell_list(r.choice(alphas), r.randrange(0, 4), cells)))
    cc["ctab"], cc["nlists"] = c.add(cell_table(lists)), len(lists)
    cc["font"], cc["lists"], cc["alphas"] = fnt, lists, alphas         # --c3x builds at the limits beside them (no draw here)
    cc["rains"] = [c.add(rain_rec(r.randrange(1, 40), r.randrange(1, 9), r.randrange(4, 14), r.randrange(6, 16), fnt,
                                  r.choice(alphas), r.randrange(16), r.randrange(16), rv, r.randrange(16), r.randrange(8),
                                  r.randrange(1, 6))) for rv in (0, 1, 2)]
    cc["strip"] = c.add(image2(60, 120, rnd_levels(r, 60 * 120)))
    cc["v2"] = []
    for _ in range(r.randrange(3, 8)):
        rec = rnd_prog2(r, cc, tame=r.random() < 0.67)
        cc["v2"].append((c.add(rec), rec))
    cc["c3"], cc["own_slot"] = True, None
    cc["rec_bytes"] = {o4: d for o4, d in c.entries}  # C3's records too, for the cache writes over what instances read

def rnd_area2(r, avoid=None):
    """A rect inside the panel, apart from `avoid` when given (None after twenty tries)."""
    for _ in range(20):
        w, h = r.randrange(8, 320), r.randrange(4, 240)
        a = (r.randrange(0, 640 - w + 1), r.randrange(0, 480 - h + 1), w, h)
        if avoid is None or not (a[0] < avoid[0] + avoid[2] and avoid[0] < a[0] + a[2] and a[1] < avoid[1] + avoid[3] and avoid[1] < a[1] + a[3]):
            return a
    return None

def rnd_seg2(r, cc, own, area, pair=None):
    """One version-2 segment of a track that is not in place (§7.2's verbs, kinds and fields; values mostly in range).
    --c3x: [pair] (the right rect's shift on a per-lens track) gives the segment its R side; an ease-2 table of the
    segment's own n (`rnd_ease`); a pref 14–17 now and then (`rnd_pref2`)."""
    l, t, w, h = area
    n, lv, ease = r.randrange(1, 9), (r.randrange(16), r.randrange(16)), r.choice([0, 0, 1])
    v = r.choice(["wait", "fill", "draw", "draw", "reveal", "cells", "rain"] + (["new", "pieces", "pieces", "rnew"] if own else []))
    xy = dict(x=l + r.randrange(-40, 41), y=t + r.randrange(-30, 31))
    if cc.get("c3x"): return rnd_seg2x(r, cc, own, area, pair, n, lv, ease, v, xy)
    if v == "wait": return w2(n)
    if v == "fill": return f2(n, r.randrange(16))
    if v in ("draw", "new"):
        kw = dict(dx=r.randrange(-120, 121), dy=r.randrange(-60, 61), lvl=lv, opt=r.choice([0, 0x10, 0x20, 0x30]), ease=ease)
        if r.random() < 0.3: kw.update(w0=r.randrange(1, 300), w1=r.randrange(1, 300), anchor=anc(r.randrange(3), r.randrange(3)))
        if r.random() < 0.3: kw.update(h0=r.randrange(1, 200), h1=r.randrange(1, 200), anchor=anc(r.randrange(3), r.randrange(3)))
        if v == "new": return dnew(n, l, t, **kw)
        if r.random() < 0.35:                         # a source window of the strip (§7.5)
            a1 = r.randrange(1, 40)
            return d2(n, cc["strip"], **xy, aux0=r.randrange(0, 121 - a1), aux1=a1, **kw)
        return d2(n, r.choice(cc["recs"]), **xy, **kw)
    if v in ("reveal", "rnew"):
        edge = r.randrange(4) | r.choice([0, SLIDE])
        if v == "rnew": return r2(n, edge, K_NEW, l, t, ease=ease)
        if r.random() < 0.5: return r2(n, edge, K_LEVEL, **xy, src=r.randrange(16), w=r.randrange(1, 80), h=r.randrange(1, 60), ease=ease)
        return r2(n, edge, 0, **xy, src=r.choice(cc["recs"]), ease=ease)
    if v == "pieces":
        model = r.choice([UNIFORM, RADIAL, RANDOM])
        grid = (1, 1) if model == UNIFORM else (r.randrange(1, 17), r.randrange(1, 17))
        stg = r.choice([0, 0, 0, 1, 3]) if n > 3 else 0
        return pc(n, K_NEW, grid=grid, model=model, stagger=stg, x=l + r.randrange(w), y=t + r.randrange(h),
                  dx=0 if model == RADIAL else r.randrange(-100, 101), dy=0 if model == RADIAL else r.randrange(-60, 61),
                  lvl=lv, seed=r.randrange(0x10000), f=r.choice([(0, 4096), (4096, 0), (0, -2048), (1024, 3000)]),
                  opt=r.choice([0, 0x10, 0x40, 0x80, 0xD0]), ease=ease)
    if v == "cells":
        return gly(max(n, 4), CELLS, cc["ctab"], r.randrange(cc["nlists"]), x=r.randrange(-30, 31), y=r.randrange(-20, 21),
                   dx=r.randrange(-20, 21), dy=r.randrange(-10, 11), lvl=lv, seed=r.randrange(0x10000), opt=r.choice([0, 0x80]), ease=ease)
    return gly(n, RAIN, cc["rains"][0], x=r.randrange(-5, 6), y=r.randrange(-5, 6), dx=r.randrange(-20, 21), dy=r.randrange(-10, 11),
               lvl=lv, seed=r.randrange(0x10000), ease=ease)

def rnd_ease(r, cc, n):
    """--c3x: an ease-2 table of n entries (`build_c3x_tables`) one segment in three — the falling one one time in four —
    or None."""
    if r.random() < 0.33:
        rise, fall = cc["etab"][n]
        return fall if fall is not None and r.random() < 0.25 else rise
    return None

def rnd_pref2(r, fields, P_unused=True):
    """--c3x: a pref writing a parameter into one of [fields] (§7.2's 14–17 among them), or into a field the verb does
    not use one time in six (5 when the parameter is not 0); 0 (none) two times in three."""
    if r.random() < 0.67: return 0
    f = r.choice(fields) if fields and not (P_unused and r.random() < 0.17) else r.choice([F_W1, F_AUX1, 12])
    return pref(r.randrange(4), f)

def rnd_seg2x(r, cc, own, area, pair, n, lv, ease, v, xy):
    """`rnd_seg2` under --c3x: the same verbs, with ease 2, the R side of a per-lens track and prefs 14–17."""
    l, t, w, h = area
    tab = rnd_ease(r, cc, n)
    ez = dict(ease=TABLE, table=tab) if tab is not None else dict(ease=ease)
    rk = {} if pair is None else dict(xR=xy["x"] + pair + r.choice([0, 0, 1, -2]))
    if v == "wait": return w2(n)
    if v == "fill": return f2(n, r.randrange(16), pref0=rnd_pref2(r, []))
    if v in ("draw", "new"):
        kw = dict(dx=r.randrange(-120, 121), dy=r.randrange(-60, 61), lvl=lv, opt=r.choice([0, 0, 0x10, 0x20, 0x30]), **ez)
        if pair is not None: kw["dxR"] = kw["dx"] + r.choice([0, 0, 3, -5])
        if r.random() < 0.3: kw.update(w0=r.randrange(1, 300), w1=r.randrange(1, 300), anchor=anc(r.randrange(3), r.randrange(3)),
                                       pref0=rnd_pref2(r, [F_W0, F_W1]))
        if r.random() < 0.3: kw.update(h0=r.randrange(1, 200), h1=r.randrange(1, 200), anchor=anc(r.randrange(3), r.randrange(3)))
        if v == "new": return dnew(n, l, t, **({"xR": l + pair} if pair is not None else {}), **kw)
        if r.random() < 0.35:                         # a source window of the strip (§7.5), its rows by a parameter now and then
            a1 = r.randrange(1, 40)
            return d2(n, cc["strip"], **xy, **rk, aux0=r.randrange(0, 121 - a1), aux1=a1, pref1=rnd_pref2(r, [F_AUX0, F_AUX1]), **kw)
        return d2(n, r.choice(cc["recs"]), **xy, **rk, **kw)
    if v in ("reveal", "rnew"):
        edge = r.randrange(4) | r.choice([0, SLIDE])
        if v == "rnew": return r2(n, edge, K_NEW, l, t, xR=None if pair is None else l + pair, **ez)
        if r.random() < 0.5: return r2(n, edge, K_LEVEL, **xy, **rk, src=r.randrange(16), w=r.randrange(1, 80), h=r.randrange(1, 60), **ez)
        return r2(n, edge, 0, **xy, **rk, src=r.choice(cc["recs"]), **ez)
    if v == "pieces":
        model = r.choice([UNIFORM, RADIAL, RANDOM])
        grid = (1, 1) if model == UNIFORM else (r.randrange(1, 17), r.randrange(1, 17))
        stg = r.choice([0, 0, 0, 1, 3]) if n > 3 else 0
        if tab is not None: ez = dict(ease=TABLE, table=cc["etab"][n - stg][0 if r.random() < 0.85 or n - stg < 3 else 1])
        dx = 0 if model == RADIAL else r.randrange(-100, 101)
        pk = {} if pair is None else dict(xR=l + r.randrange(w) + pair, dxR=0 if model == RADIAL else dx + r.choice([0, 4, -7]))
        return pc(n, K_NEW, grid=grid, model=model, stagger=stg, x=l + r.randrange(w), y=t + r.randrange(h),
                  dx=dx, dy=0 if model == RADIAL else r.randrange(-60, 61), **pk,
                  lvl=lv, seed=r.randrange(0x10000), f=r.choice([(0, 4096), (4096, 0), (0, -2048), (1024, 3000)]),
                  opt=r.choice([0, 0x10, 0x40, 0x80, 0xD0]), pref0=rnd_pref2(r, [F_W0, F_W1, F_AUX1]), **ez)
    if v == "cells":
        n = max(n, 4)
        if tab is not None: ez = dict(ease=TABLE, table=cc["etab"][n][0])
        ck = {} if pair is None else dict(xR=r.randrange(-30, 31), dxR=r.randrange(-20, 21))
        return gly(n, CELLS, cc["ctab"], r.randrange(cc["nlists"]), x=r.randrange(-30, 31), y=r.randrange(-20, 21), **ck,
                   dx=r.randrange(-20, 21), dy=r.randrange(-10, 11), lvl=lv, seed=r.randrange(0x10000), opt=r.choice([0, 0x80]),
                   pref0=rnd_pref2(r, [F_AUX0]), **ez)
    rk = {} if pair is None else dict(xR=r.randrange(-5, 6), dxR=r.randrange(-20, 21))
    return gly(n, RAIN, cc["rains"][0], x=r.randrange(-5, 6), y=r.randrange(-5, 6), dx=r.randrange(-20, 21), dy=r.randrange(-10, 11),
               **rk, lvl=lv, seed=r.randrange(0x10000), **ez)

def rnd_prog2(r, cc, tame):
    """A version-2 program (§7.2): OWN (a third with LAND) or bound-shaped. A first in-place track (PIECES layer old,
    SHRINK, RAIN reveal 1 or 2) only in OWN, in a shape that keeps the fold condition: when it moves pixels every later
    track lies apart from it, and a later track meeting a still one paints its whole area (a level or `new` backdrop).
    Untamed, one field is then perturbed (header flags, a verb, a kind, a reserved or unused byte, a range)."""
    own = r.random() < 0.6
    fl = (OWN | (LAND if r.random() < 0.35 else 0)) if own else 0
    tracks, moving, A = [], False, None
    if own and r.random() < 0.5:
        A = rnd_area2(r)
        l, t, w, h = A
        n, kind = r.randrange(1, 9), r.randrange(3)
        lead = [w2(r.randrange(1, 3))] if r.random() < 0.2 else []
        if kind == 0:
            model = r.choice([UNIFORM, RADIAL, RANDOM])
            grid = (1, 1) if model == UNIFORM else ((1, r.randrange(1, 17)) if model == RANDOM else (r.randrange(1, 9), r.randrange(1, 9)))
            dx = 0 if model == RADIAL else r.randrange(-80, 81)
            dy = r.randrange(-40, 41) if model == UNIFORM else 0
            f1 = r.choice([0, 1024, 2048, 4096, 6000])
            sg = pc(n, K_OLD, grid=grid, model=model, x=l + r.randrange(w), y=t + r.randrange(h), dx=dx, dy=dy,
                    lvl=(15, r.randrange(16)), seed=r.randrange(0x10000), f=(0, f1), vacate=r.randrange(16), ease=r.choice([0, 1]))
            moving = bool(f1) and (model == RADIAL or bool(dx) or bool(dy))
        elif kind == 1:
            wsc = (w, r.randrange(0, w + 1)) if r.random() < 0.6 else (0, 0)
            hsc = (h, r.randrange(0, h + 1)) if r.random() < 0.6 or wsc == (0, 0) else (0, 0)
            sg = sh(n, w=wsc, h=hsc, an=anc(r.randrange(3), r.randrange(3)), vacate=r.randrange(16), lvl=(15, r.randrange(16)),
                    ease=r.choice([0, 1]))
            moving = wsc != (0, 0) or hsc != (0, 0)
        else:
            sg = gly(n, RAIN, cc["rains"][r.choice([1, 2])], x=r.randrange(-4, 5), seed=r.randrange(0x10000),
                     lvl=(r.randrange(16), 15), ease=r.choice([0, 1]))
        tracks.append(track(rect(*A), 0xFF, *lead, sg))
    whole = []                                        # --c3x: single-rect tracks that paint their whole area
    for _ in range(r.randrange(0 if tracks else 1, 4)):
        area = rnd_area2(r, A if moving else None)
        if area is None: continue
        bd = r.choice([r.randrange(16), r.randrange(16), r.randrange(16)] + ([BD_NEW] if own else []))
        if cc.get("c3x") and r.random() < 0.3:        # a per-lens track: one size, one row range, the right rect shifted
            l, t, w, h = area
            lR = min(640 - w, max(0, l + r.randrange(-24, 25)))
            if not (A is not None and moving and overlaps((lR, t, w, h), A)):
                tracks.append(track((rect(*area), rect(lR, t, w, h)), bd,
                                    *[rnd_seg2(r, cc, own, area, pair=lR - l) for _ in range(r.randrange(1, 3))]))
                continue
        tracks.append(track(rect(*area), bd, *[rnd_seg2(r, cc, own, area) for _ in range(r.randrange(1, 3))]))
        whole.append(area)
    if cc.get("c3x") and len(tracks) < 8 and r.random() < 0.4:
        # backdrop none (§7.2's rule for a version-2 record): a lone REVEAL between WAITs in an OWN program (its ease never
        # falls), or a track inside an earlier one that paints its whole area — apart from any in-place track either way
        if own and (not whole or r.random() < 0.5):
            area = rnd_area2(r, A)
            if area is not None:
                l, t, w, h = area
                n_ = r.randrange(1, 9)
                tab = cc["etab"][n_][0] if r.random() < 0.3 else None
                ez = dict(ease=TABLE, table=tab) if tab is not None else dict(ease=r.choice([0, 1]))
                kind = r.choice([K_LEVEL, 0, K_NEW])
                rv = (r2(n_, r.randrange(4) | r.choice([0, SLIDE]), K_NEW, l, t, **ez) if kind == K_NEW else
                      r2(n_, r.randrange(4), K_LEVEL, l, t, src=r.randrange(16), w=r.randrange(1, w + 1), h=r.randrange(1, h + 1), **ez)
                      if kind == K_LEVEL else r2(n_, r.randrange(4), 0, l, t, src=r.choice(cc["recs"]), **ez))
                tracks.append(track(rect(*area), 0xFF, *([w2(r.randrange(1, 3))] if r.random() < 0.3 else []), rv))
        elif whole:
            l, t, w, h = r.choice(whole)
            w2_, h2_ = r.randrange(1, w + 1), r.randrange(1, h + 1)
            inner = (l + r.randrange(0, w - w2_ + 1), t + r.randrange(0, h - h2_ + 1), w2_, h2_)
            if A is None or not overlaps(inner, A):
                tracks.append(track(rect(*inner), 0xFF, rnd_seg2(r, cc, own, inner)))
    rec = bytearray(prog2(*tracks, fl=fl, reg=False))
    if not tame:                                      # one field perturbed
        k = r.random()
        if k < 0.15: rec[4] = r.choice([LAND, 0x04, 0x80, OWN | 0x08])
        elif k < 0.25: rec[r.choice([5, 6, 7])] = r.randrange(1, 256)
        elif len(rec) > 12:
            seg0 = 8 + 4 + (16 if rec[10] else 8)     # the first track's first segment
            off = seg0 + r.choice([0, 3, 4, 14, 15, 16, 20, 25, 28, 30, 32, 34, 36, 38])
            if off < len(rec): rec[off] = r.randrange(256)
    return bytes(rec)

def rnd_play2(r, cc, slot, pv):
    """A PLAY of a version-2 program, or a PLAY-AFTER (§7.7) — after the slot of the last owning PLAY two times in three."""
    p4, rec = r.choice(cc["v2"]) if r.random() < 0.93 else (r.randrange(0x10000), b"")
    period = r.choice([16, 34, 34, 50, 128, r.randrange(15, 130)])
    P = [pv() for _ in range(4)] if r.random() < 0.3 else [0, 0, 0, 0]
    if cc.get("c3x"):
        if r.random() < 0.6: p4, rec = r.choice(cc["heavy"])                # a program priced at its share of the cap
        if r.random() < 0.5: P = [r.choice([0, 0, 1, 2, 3, 5, 10, 20, 60, 100, 119, 120, 300, 2048, 4096, -1]) for _ in range(4)]
    tag = r.randrange(0x10000)
    owning = len(rec) > 4 and rec[0] == 2 and rec[4] & OWN
    if r.random() < 0.4:
        after = cc["own_slot"] if cc["own_slot"] is not None and r.random() < 0.67 else r.choice([0, 1, 2, 3, 4])
        sl = r.choice([s_ for s_ in range(4) if s_ != after] + ([after] if r.random() < 0.05 else []))
        if owning: cc["own_slot"] = sl
        return play_after(sl, period, p4, tag, after, P)
    sl = slot()
    if owning and sl < 4: cc["own_slot"] = sl
    return play(sl, period, p4, tag, cc["cap"], P)

def rnd_ring(r, cc):
    """LIST-RING (§7.8: valid and invalid flags, shifts past both ends) or a SET with a kind-3 parameter."""
    lst = r.choice([0, 0, 1, 2, 3, 4])
    if r.random() < 0.55:
        return bind_ring(lst, cc["itab"] if r.random() < 0.8 else r.choice([NONE, r.randrange(0x10000)]),
                         r.choice([1, 2, 4, 8, 8, 0, 1025]), r.choice([0, 0, 1, -1, 7, -9, 2000, -32768]),
                         r.choice([10, -10, 0, 20000]), r.randrange(256), r.choice([1, 1, 1, 0, 2, 0x80]))
    ev = r.choice([4, 5])
    return bind_set(r.randrange(8), 0xFF, ev, lst, 1 if ev == 4 else -1, r.choice([0, 0, 1, 2, 3]), 34,
                    r.choice(cc["lprogs"]), [(K_CUR, r.choice([0, 0, 1, -1, 32767, -32768]))] + [(2, 0)] * r.randrange(0, 3))

# ---- --c3x (stage X2D, `C3-decisions.md` Y, C-2): what `--c3` never made. Only gen_c1's c3 path with C3X reaches it.
def overlaps(a, b):
    return a[0] < b[0] + b[2] and b[0] < a[0] + a[2] and a[1] < b[1] + b[3] and b[1] < a[1] + a[3]

def build_c3x_tables(r, c, cc):
    """Ease-2 tables (§5.4: n entries, E[n] = 4096) for every step count 1..8: one whose E never falls and, from n = 3,
    one that falls once — a segment of n steps (n' under stagger) names one of its own n."""
    cc["etab"] = {}
    for n in range(1, 9):
        rise = sorted(r.randrange(0, 4097) for _ in range(n - 1)) + [4096]
        fall = None
        if n >= 3:
            f = list(rise)
            k = r.randrange(1, n - 1)                 # E[k + 1] below E[k]
            f[k - 1] = max(f[k - 1], 600)
            f[k] = f[k - 1] - r.randrange(1, f[k - 1] + 1)
            fall = c.add(easing(f))
        cc["etab"][n] = (c.add(easing(rise)), fall)

def tune_x(c, region, target, own, rec4=None):
    """A program inside `region` (l, t, w, h) whose §7.12 C is exactly `target`, T 8: tracks of the region and one of
    w × q0 — OWN two-axis DRAWs of `new` over backdrop `new` (1,024 + 11·A), or bound two-axis DRAWs of the record
    `rec4` over a level (1,024 + 26·L + Ha·(11·w + its width)) — then two level backdrops over a WAIT (1,024 + A) of
    w × q2 and k2 × 1. None when the region cannot hold it in eight tracks."""
    l, t, w, h = region
    fl = OWN if own else 0
    def big(hh): return (track(rect(l, t, w, hh), BD_NEW, dnew(8, l, t, w0=1, w1=1)) if own else
                         track(rect(l, t, w, hh), 3, d2(8, rec4, l, t, w0=1, w1=1)))
    def lvl(ww, hh): return track(rect(l, t, ww, hh), 2, w2(8))
    cost1 = lambda tr: pcost(c, prog2(tr, fl=fl, reg=False))
    b0 = cost1(big(2)) - cost1(big(1)); a0 = cost1(big(1)) - b0; unit = cost1(big(h))
    assert unit == a0 + b0 * h
    n = (target - 2048 - a0 - b0 - w - 1) // unit
    if not 0 <= n <= 5: return None
    m = target - n * unit
    q0 = min(h, (m - 2048 - a0 - w - 1) // b0)
    rem = m - 2048 - a0 - b0 * q0
    q2 = (rem - 1) // w; k2 = rem - q2 * w
    if not (1 <= q0 <= h and 1 <= q2 <= h and 1 <= k2 <= w): return None
    rec = prog2(*([big(h)] * n), big(q0), lvl(w, q2), lvl(k2, 1), fl=fl, reg=False)
    assert pcost(c, rec) == target, (pcost(c, rec), target)
    return rec

def build_c3x(r, c, cc):
    """The programs priced at the cap's shares (`cc["heavy"]`); GLYPHS at their limits; a source window and a cell index
    set by a parameter, in `cc["v2"]` and as list programs. `cc["rec_bytes"]` is taken again at the end."""
    cc["heavy"] = []
    half, third = CAP // 2, CAP // 3
    plan = [((0, 0, 320, 480), half), ((320, 0, 320, 480), CAP - half), ((320, 0, 320, 480), CAP - half + 1),
            ((0, 0, 213, 480), third), ((213, 0, 214, 480), third), ((427, 0, 213, 480), CAP - 2 * third),
            ((427, 0, 213, 480), CAP - 2 * third + 1), ((0, 0, 640, 480), CAP), ((0, 0, 640, 480), CAP + 1)]
    for region, target in plan:
        own = r.random() < 0.6
        rec = tune_x(c, region, target, own, r.choice(cc["recs"]))
        if rec is None: rec = tune_x(c, region, target, True)
        if rec is not None: cc["heavy"].append((c.add(rec), rec))
    fnt = cc["font"]
    al224 = c.add(alphabet(list(range(32, 256))))     # every code an alphabet may hold
    big = c.add(cell_list(al224, r.randrange(0, 4), [cell(r.randrange(-10, 630), r.randrange(-10, 470), r.randrange(1, 20),
                r.randrange(1, 24), fnt, r.choice([0, 32, r.randrange(33, 256)]), r.choice([0, 32, r.randrange(33, 256)]),
                r.choice([0xFF, r.randrange(16)]), r.choice([15, 8, 0x2F, r.randrange(16)])) for _ in range(512)]))
    t1024 = c.add(cell_table([big] + [r.choice(cc["lists"] + [big]) for _ in range(1023)]))
    rv = r.choice([0, 0, 2])                          # reveal 2: in place, so OWN, first, over no backdrop (§7.2)
    rain = c.add(rain_rec(128, 32, r.choice([1, 2, 4, 5]), r.choice([2, 3, 6, 8]), fnt, r.choice([al224] + cc["alphas"]),
                          r.randrange(16), r.randrange(16), rv, r.randrange(16), r.randrange(8), r.randrange(1, 6)))
    win = prog2(track(rect(40, 300, 240, 40), r.randrange(16), d2(r.randrange(1, 9), cc["strip"], 50, 305, aux1=r.randrange(1, 40),
                                                                     pref0=pref(0, F_AUX0))), reg=False)      # rows by P0
    idx = prog2(track(rect(0, 0, 640, 480), r.randrange(16), gly(r.randrange(4, 9), CELLS, t1024, 0, pref0=pref(1, F_AUX0),
                                                                    seed=r.randrange(0x10000))), reg=False)   # index by P1
    lim = [prog2(track(rect(0, 0, 640, 480), r.randrange(16), gly(r.randrange(4, 9), CELLS, t1024, r.randrange(1024),
                                                                 seed=r.randrange(0x10000))), reg=False),
           prog2(track(rect(0, 0, 640, 480), 0xFF if rv == 2 else r.randrange(16), gly(r.randrange(1, 9), RAIN, rain, seed=r.randrange(0x10000))),
                 fl=OWN if rv == 2 or r.random() < 0.5 else 0, reg=False)]
    for rec in [win, idx] + lim:
        o4 = c.add(rec)
        cc["v2"].append((o4, rec))
        if rec in (win, idx): cc["lprogs"].append(o4)  # a SET's kind 2 or 3 parameter drives them on a list
    cc["rec_bytes"] = {o4: d for o4, d in c.entries}

# ---- --dw (with --c3; stage DW-3h, `C3-decisions.md` EB): the app switcher's shapes `--c3` never made. Only gen_c1's c3
# path with DW reaches it.
DW_ARGS = {1: (-2, -1, 0, 1), -1: (2, 1, 0, -1)}   # the wheel's kind-1 arguments (the DW spec §2.2; `vectors_v5_dw`)

def dw_set(entry, lst, stp, slot, period, p4, args):
    """A wheel scroll entry: devType any, event 4 (step +1) or 5 (step −1), every parameter kind 1 (§6.3, §7.8)."""
    return bind_set(entry, ANY, UP if stp > 0 else DOWN, lst, stp, slot, period, p4, [(K_ITEM, a) for a in args])

def build_dw(r, c, cc):
    """The wheel's records at its geometry at 480 (`make_vectors.WHEEL_GEO`): its rule, 2–5 item cells, an item table of
    1–10 of them, the drum's two ease tables and the down and up programs (`wheel_prog`: version-2, not OWN, seven
    per-lens tracks over a level-0 backdrop, each DRAW's source a kind-1 parameter) over 2..19 steps."""
    g, t = WHEEL_GEO, r.choice([19, 19, 15, 8, 3, 2])
    hC, hN, w = g["bandBot"] - g["bandTop"] - 2, g["hN"], g["panel"][2]
    rule4 = c.add(image2(w, 2, [r.randrange(16)] * (2 * w)))
    cells = [c.add(image2(w, hC + hN, wheel_cell(r.randrange(10), w, hC, hN))) for _ in range(r.randrange(2, 6))]
    cc["dw_items"] = [r.choice(cells) for _ in range(r.choice([1, 2, 3, 4, 4, 9, 10]))]
    cc["dw_itab"] = c.add(b"".join(u16(o) for o in cc["dw_items"]))
    e1, e2 = wheel_tables(t)
    tabs = ((c.add(easing(e1)), e1), (c.add(easing(e2)), e2))
    cc["dw_prog"] = {s_: c.add(wheel_prog(g, cells[0], rule4, s_, t, tabs)) for s_ in (1, -1)}
    cc["dw"] = True
    cc["rec_bytes"] = {o4: d for o4, d in c.entries}  # the wheel's records too, for the cache writes over what instances read

def dw_table(r, cc, table):
    """The start's table under --dw: list 2 a ring over the wheel's items with entries 6 and 7 — behind Main's entries 0
    and 1 (closed: the lowest-numbered match fires, §6.2 step 3), or the wheel open (Main's entries 0–2 not bound, entry 0
    the report-only tap on list 2, as the open's batch leaves them)."""
    n = len(cc["dw_items"])
    wheel = [msg(bind_ring(2, cc["dw_itab"], n, r.randrange(n), 0, r.randrange(256), 1)),
             msg(dw_set(6, 2, 1, 0, 16, cc["dw_prog"][1], DW_ARGS[1])), msg(dw_set(7, 2, -1, 0, 16, cc["dw_prog"][-1], DW_ARGS[-1]))]
    if r.random() < 0.5: return table[:2] + wheel + [msg(bind_set(0, RING, TAP, 2, 0, 0, 16, NONE))]
    return table + wheel

def rnd_dw(r, cc):
    """A wheel message: a SET of a wheel program (entry 6 down on event 4, entry 7 up on event 5, mostly on list 2 with the
    phone's kind-1 arguments), the report-only tap SET for list 2 or back for list 0 or 1 (one entry held by two lists in
    turn), LIST-RING of list 2 over the wheel's item table with a new mark, a CLEAR of the cover's entries or of 6 and 7,
    the item table rewritten in place (mode 19, the items in another order), or the COMMIT of a wheel program with item
    parameters (S near the last fire RIGHT counted, the cursor in range or one past it)."""
    k, items = r.random(), cc["dw_items"]
    if k < 0.3:
        stp = r.choice([1, -1])
        e = (6 if stp > 0 else 7) if r.random() < 0.8 else r.randrange(8)
        args = DW_ARGS[stp] if r.random() < 0.8 else [r.choice([-3, -2, -1, 0, 1, 2, 3, 9, -10]) for _ in range(4)]
        return dw_set(e, r.choice([2, 2, 2, 0, 1]), stp, r.choice([0, 0, 0, 1]), r.choice([16, 16, 34]),
                      cc["dw_prog"][stp if r.random() < 0.9 else -stp], args)
    if k < 0.5: return bind_set(r.choice([0, 0, 2]), RING, TAP, r.choice([2, 2, 0, 1]), 0, 0, 16, NONE)
    if k < 0.7:
        n = len(items)
        return bind_ring(2 if r.random() < 0.85 else r.choice([0, 1]), cc["dw_itab"], n if r.random() < 0.85 else r.choice([1, 3, 4, 9, 10]),
                         r.choice([0, 0, 1, -1, 4, -5, n]), 0, r.randrange(256), 1)
    if k < 0.8: return bind_clear(r.choice([0, 1, 2, 6, 7]))
    if k < 0.9:
        moved = items[:]
        r.shuffle(moved)
        return cache_write2((cc["dw_itab"], b"".join(u16(o) for o in moved)))
    S = (cc["seq"] + r.choice([-1, 0, 0, 1])) & 0xFFFF
    return commit(0, S, 2, r.randrange(len(items) + 1), cc["dw_prog"][r.choice([1, -1])], [r.choice(items) for _ in range(4)])

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

def carries(m, mode):
    """`mode` at the top level, inside a batch or inside a self-test step (`is_engine`'s walk, for any mode)."""
    if not m: return False
    if m[0] & 0x7F == mode: return True
    if m[0] == 16 and len(m) > 2: return carries(m[2:], mode)
    if m[0] == 8 and len(m) >= 2:
        p = 2
        for _ in range(m[1]):
            if p + 2 > len(m): return False
            ln = m[p] | m[p + 1] << 8
            if carries(m[p + 2:p + 2 + ln], mode): return True
            p += 2 + ln
    return False

def gen_c1(seed, nsteps, c2=False, c3=False):
    """C1's set; with `c2` C2's (`FIRMWARE.md` §6.10, spec §5): BIND armed and a table bound at the start (and after
    every session that comes back), inputs (ids 0, 1, 3, 4, 5, 0xD, 0xE; devTypes 0, 1, 4; one in five on LEFT alone)
    and page switches between messages, modes 27 and 28 and mode 26's subs 3..5 among them. Without `c2` every
    random draw is C1's, so a seed writes the vector it wrote before."""
    r = random.Random(seed)
    c, recs, table_off4, cc = build_cache_c1(r, c2)
    if c3 and C3X:                                    # every --c3x draw behind its switch: --c3 seeds keep their vectors
        cc["c3x"] = True
        build_c3x_tables(r, c, cc)
    if c3: build_c3(r, c, cc)                         # every draw for C3 behind its flag: --c1 and --c2 seeds keep their vectors
    if c3 and C3X: build_c3x(r, c, cc)
    if c3 and DW: build_dw(r, c, cc)                  # every --dw draw behind its switch: --c3 seeds keep their vectors
    full =DRAW2 | ENGINE | ZWRITE | (BIND if c2 else 0) | (EFFECTS if c3 else 0)
    armed = full if r.random() < 0.85 else r.choice([DRAW2 | ENGINE, DRAW2 | ZWRITE, ENGINE | ZWRITE]) | (BIND if c2 else 0)
    if c3 and armed != full and r.random() < 0.5: armed |= EFFECTS
    prelude = [{"tick": 1000}, {"lease": "acquire"}, {"cachesize": r.choice([64, 96, 160, 256])}, {"flags": armed}]
    writes = [msg(m) for m in c.messages(chunk=2500)]
    kf = msg(keyframe(pattern(W, H, seed)))
    table = []
    if c2:
        prelude.append({"page": True})
        lp = cc["lprogs"]
        table = [msg(bind_list(0, cc["itab"], 8, 0, 10, 1)), msg(bind_list(1, cc["itab"], 8, 4, -10, 1)),
                 msg(bind_set(0, 0xFF, 4, 0, 1, 0, 34, lp[0], [(2, 0)])),
                 msg(bind_set(1, 0xFF, 5, 1, -1, 1, 34, r.choice(lp), [(2, 0)])),
                 msg(bind_set(2, 4, 0, 0, 0, 0, 34, r.choice([NONE, r.choice(lp)]), [(2, 0)]))]
        if c3:                                        # list 0 a ring (§7.8), entry 0 by the cursor before the move (kind 3)
            if r.random() < 0.7: table[0] = msg(bind_ring(0, cc["itab"], 8, 0, 10, 1, 1))
            if r.random() < 0.5: table[2] = msg(bind_set(0, 0xFF, 4, 0, 1, 0, 34, lp[0], [(K_CUR, r.choice([0, 0, 10, 32767]))]))
        if c3 and DW: table = dw_table(r, cc, table)
    steps = [step(*prelude), *[step(w) for w in writes], step(kf), step({"tick": 1001}, *table, wake())]
    now, fid = 1001, 1
    last = (now, 34)                                  # the last PLAY's (or fire's) tick and period: wakes aim at its steps
    for _ in range(nsteps):
        ops = []
        for _ in range(r.randrange(1, 4)):
            if c2:
                x = r.random()
                if x < 0.22:                          # an input between messages (§6.10), after a tick
                    ev, dev = r.choice([0, 0, 1, 3, 4, 4, 5, 5, 0xD, 0xE]), r.choice([0, 1, 4, 4])
                    now += r.choice([0, 1, r.randrange(1, 60)])
                    o = {"input": [dev, ev, r.randrange(200)]}
                    if r.random() < 0.2: o["only"] = "L"
                    elif ev in (4, 5) or (ev == 0 and dev == 4): cc["seq"] += 1      # RIGHT's S, as the phone would count it (roughly)
                    if ev in (4, 5, 0): last = (now, 34)
                    ops += [{"tick": now}, o]; continue
                if x < 0.25:
                    ops.append({"page": r.random() < 0.65}); continue
            k = r.random()
            if k < 0.03:
                now += r.randrange(1, 20_000); ops.append({"tick": now}); continue
            if k < 0.05:
                ev = r.choice(["acquire", "release"])
                ops.append({"lease": ev})
                if ev == "release" and r.random() < 0.7:          # most sessions come back: a fresh lease, armed, its cache
                    ops += [{"lease": "acquire"}, {"flags": full}, kf, *writes]
                    if c2: ops += [{"tick": now}, *table]          # and its table: the release point emptied it
                if c2 and ev == "release": cc["seq"] = 0
                continue
            if k < 0.07:
                ops.append({"cachesize": r.choice([32, 63, 64, 160, 161, 256, 257])}); continue
            if k < 0.10:
                fl = [0, DRAW2, full, DRAW2 | ENGINE, DRAW2 | ZWRITE, ENGINE, full | PROBE, 0xFFFF]
                if c2: fl += [DRAW2 | BIND, DRAW2 | ENGINE | ZWRITE, ENGINE | BIND, full, full]
                if c3: fl += [full & ~EFFECTS, EFFECTS, full, full]
                ops.append({"flags": r.choice(fl)}); continue
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
                extra = [{"lease": "acquire"}, {"flags": full}, kf, *writes] + ([{"tick": now}, *table] if c2 else [])
            else:
                extra = []
            if CORRUPT and r.random() < 0.12: m = corrupt(r, m)
            if is_engine(m) or (c2 and carries(m, 27)):
                now += r.choice([0, 1, r.randrange(1, 60)])
                ops.append({"tick": now})
                if m[0] & 0x7F == 26 and len(m) > 4 and m[1] == 0: last = (now, max(1, m[3]))
                if c3 and m[0] & 0x7F == 26 and len(m) == 17 and m[1] == 8: last = (now + 4 * max(1, m[3]), max(1, m[3]))
            ops.append(msg(m))
            ops += extra
        steps.append(step(*ops))
    return {"name": f"fuzz{'dw' if c3 and DW else 'c3' if c3 else 'c2' if c2 else 'c1'}-{seed:04d}", "contract": 2, "panel": True,
            "features": FEATURES_C3 if c3 else FEATURES_C2 if c2 else FEATURES_C1, "steps": steps}

def main():
    global CORRUPT, C1, C2, C3, C3X, DW
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("out", help="directory to write the vectors into")
    ap.add_argument("count", type=int, help="how many vectors")
    ap.add_argument("steps", type=int, help="random steps per vector (a prelude and a keyframe precede them)")
    ap.add_argument("--seed", type=int, default=1, help="the first vector's seed; each one after it is seed+i")
    ap.add_argument("--corrupt", action="store_true", help="truncate or flip a byte in one message in eight")
    ap.add_argument("--c1", action="store_true", help="C1's set: modes 25 and 26, wakes, mode 11 (FIRMWARE.md §5)")
    ap.add_argument("--c2", action="store_true", help="C2's set: C1's, plus modes 27 and 28, mode 26 subs 3..5, inputs "
                    "and page switches, BIND (FIRMWARE.md §6)")
    ap.add_argument("--c3", action="store_true", help="C3's set: C2's, plus version-2 programs over every verb, OWN and "
                    "LAND, PLAY-AFTER, LIST-RING and kind 3, EFFECTS (FIRMWARE.md §7)")
    ap.add_argument("--c3x", action="store_true", help="with --c3: the shapes --c3 never made — costs at the cap's shares, "
                    "ease-2 tables, backdrop-none later tracks, per-lens tracks, prefs 14-17, GLYPHS at their limits, "
                    "windows and cell indexes by a parameter (stage X2D)")
    ap.add_argument("--dw", action="store_true", help="with --c3: the app switcher's shapes --c3 never made — the wheel's "
                    "version-2 programs bound as entries 6 and 7 on list 2 as a ring, the report-only tap held by two "
                    "lists in turn, the item table rewritten in place (stage DW-3h)")
    a = ap.parse_args()
    CORRUPT = a.corrupt
    C1, C2, C3, C3X, DW = a.c1, a.c2, a.c3, a.c3x, a.dw
    if C1 + C2 + C3 > 1: ap.error("--c1, --c2 and --c3 are three sets; give one")
    if C3X and not C3: ap.error("--c3x extends --c3; give both")
    if DW and not C3: ap.error("--dw extends --c3; give both")
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    for i in range(a.count):
        v = gen(a.seed + i, a.steps)
        (out / f"{v['name']}.json").write_text(json.dumps(v, indent=1) + "\n")
    print(f"wrote {a.count} vector(s) of {a.steps} random step(s) to {out}"
          + (" (one message in eight corrupted)" if CORRUPT else ""))


if __name__ == "__main__":
    main()
