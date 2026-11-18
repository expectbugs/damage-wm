#!/usr/bin/env python3
"""The snapshot judge (`CONTRACT.md` §11.1; build S1-1 spec §5): compare two `desktop --snapshot` sets pixel for pixel.

Context for the reader: Damage is Adam's own window manager for his own glasses; `desktop --snapshot DIR` renders
what the left lens panel holds after each scripted scene (the post-wire truth, through the simulator firmware). A
window's conversion must leave every scene as it was; this tool says, scene by scene, what changed.

    python3 tools/snapdiff.py BASE NEW [--mask BASE2] [--subset]
    python3 tools/snapdiff.py --selftest

BASE and NEW are directories of PNGs named by scene (`01-main-active.png` ...). For every scene the tool prints the
number of differing pixels and their bounding boxes, split into those INSIDE the mask and those OUTSIDE it. The mask is:
  - the union of the pixels that differ between BASE and BASE2 for that scene (two renders of one tree: whatever
    differs between them is live content, not the change under test), when --mask is given;
  - the declared live cells (`HANDOFF.md` §79.4): the clock cell of the top bar; the status bar's throughput, compass
    tape and link cells (at the height the scene is drawn at, found from its bottom divider); silent mode's clock; disk
    free in `11-files-locations`; a Feed article's and a Feed comic's meta line (its age); the clock of a notice box
    raised from the wall clock (`25-torrents-done-notice`, `09f-tmux-alert-main`, `78-feed-notice-main`); the whole of
    `38-*` / `39-*` (Music Mode's visualizer runs from the live clock).
A difference OUTSIDE the mask is a finding: the stage that ran the judge names it or fixes it. A scene present in one
set and not the other is a failure. With --subset, NEW may hold fewer scenes than BASE (the phone arrangement renders
Reader's scenes only, into `DIR/phone/`): every scene in NEW must exist in BASE, and the rest of BASE is not compared.

Exit status: 0 = no difference outside the mask and no missing or extra scene; 1 = otherwise; 2 = bad arguments.
"""
from __future__ import annotations

import os
import shutil
import sys
import tempfile

# --- the declared live cells: `core/…/geom/Layout.kt` at the panel's full width -------------------------------------
PANEL_W, PANEL_H = 640, 480
TOP_H, DIV_H, STATUS_H, INSET_X = 32, 2, 28, 16     # Layout.TOP_H / DIV_H / STATUS_H / CONTENT_INSET_X
HEIGHTS = (480, 416, 352, 288)                       # the four TOP-aligned sizes (`CLAUDE.md`, compositor discipline)
# The clock cell is measured from the face (Layout.clockCell, at least CLOCK_W = 80 wide, right-aligned at 624); the
# declared cell starts left of the floor so a wider measured face stays covered. The battery gauges end before 520.
CLOCK = (520, 0, PANEL_W - 520, TOP_H + DIV_H)
# The status bar at full width: op 120 · status 140 · thru 128 · tape 100 · link 120 from x = 16 (Layout's init);
# thru, tape and link are the live cells (the readout, the compass tape, the signal bars).
# The left lens is drawn shifted sideways by the depth offset (8 px measured on the base renders at the default depth),
# so the live cells are widened by it.
SHIFT = 8
STATUS_LIVE_X = INSET_X + 120 + 140 - SHIFT          # 276 less the shift
STATUS_LIVE_W = PANEL_W - STATUS_LIVE_X
SILENT_CLOCK = (480, 0, PANEL_W - 480, 50)           # silent mode's clock (scene 10), seven segments, right-aligned: room for "12:59"
FILES_FREE = [(80, 240, 320, 30), (360, 280, 240, 160)]   # scene 11: the lens's free line; the rows' bars and free text
# a Feed article's meta line (scenes 62, 67): its age ("18s") counts from the scene's own fixture time, and a digit's
# width moves the rest of the line (seen on 67 at S1-1 P5: "17s" against the base's "18s", 626 px)
FEED_ARTICLE_META = (180, 118, 340, 20)
# a Feed comic's meta line (scene 64, "xkcd · 17s ago"): the class's other site — its age counts from the scripted feed's
# own start, so it moves with how long the run took to reach the scene (S1-1's last stage: "16s" idle, "18s" under load)
FEED_COMIC_META = (20, 70, 240, 22)
# a notice box's clock (build S1-2 J0, scene 25: a Torrents done notice): the shell stamps a window's notice with the
# wall clock (`Shell.notifyInternal`), drawn right-aligned in the box's source line (`Notifications.paint`, at
# `full.right - 8`). Measured on the base render: the 248 px box at x 196..443, "6:07" at x 408..434, y 195..203; the
# cell starts left of it for the widest "12:59" and stops above the box's rule (y 206). Scenes 07/08 pin their time.
NOTICE_CLOCK = (384, 190, 56, 16)
# the same clock in a two-line box (build S1-3 J: a Tmux alert and a Feed notice over Main, 09f and 78): the box is
# centred on its height, so it starts 12 px lower; measured on the base renders, "8:25" at x 408..433, y 207..215, the
# box's rule at y 218..219
NOTICE_CLOCK_2LINE = (384, 202, 56, 16)
NOTICE_2LINE_SCENES = ("09f-tmux-alert-main", "78-feed-notice-main")


def _load(path):
    from PIL import Image
    im = Image.open(path).convert("L")
    return im.size, im.tobytes()


def _height(px, size):
    """The height the scene is drawn at: the first of the four whose bottom divider row is lit across the bar's width.
    None when no divider is found (silent mode, an exclusive surface)."""
    w, h = size
    for hh in HEIGHTS:
        y = hh - STATUS_H - DIV_H
        if y < 0 or y >= h:
            continue
        # the divider is two rows lit across the bar's 608 px, shifted sideways by the lens's depth offset
        lit = [sum(1 for v in px[r * w:(r + 1) * w] if v) for r in (y, y + 1)]
        if min(lit) >= w - 2 * INSET_X - 8:
            return hh
    return None


def declared_cells(name, px, size):
    """The live rects for one scene, from its BASE render."""
    cells = []
    if name.startswith("38-") or name.startswith("39-"):
        return [(0, 0, size[0], size[1])]
    if "silent" in name:
        cells.append(SILENT_CLOCK)
    hh = _height(px, size)
    if hh is not None:
        cells.append(CLOCK)
        cells.append((STATUS_LIVE_X, hh - STATUS_H, STATUS_LIVE_W, STATUS_H))
    if name.startswith("11-"):
        cells.extend(FILES_FREE)
    if "feed-article" in name:
        cells.append(FEED_ARTICLE_META)
    if "feed-comic" in name:
        cells.append(FEED_COMIC_META)
    if "done-notice" in name:
        cells.append(NOTICE_CLOCK)
    if name.startswith(NOTICE_2LINE_SCENES):
        cells.append(NOTICE_CLOCK_2LINE)
    return cells


def _in_cells(x, y, cells):
    for (cx, cy, cw, ch) in cells:
        if cx <= x < cx + cw and cy <= y < cy + ch:
            return True
    return False


def _boxes(points, tile=8):
    """Bounding boxes of the differing pixels, grouped by 8-connected tiles of `tile` px (a glyph's strokes fall in one
    group; two cells apart do not)."""
    if not points:
        return []
    tiles = {}
    for (x, y) in points:
        tiles.setdefault((x // tile, y // tile), []).append((x, y))
    seen, out = set(), []
    for t in tiles:
        if t in seen:
            continue
        stack, members = [t], []
        seen.add(t)
        while stack:
            cx, cy = stack.pop()
            members.append((cx, cy))
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    n = (cx + dx, cy + dy)
                    if n in tiles and n not in seen:
                        seen.add(n)
                        stack.append(n)
        pts = [p for m in members for p in tiles[m]]
        xs = [p[0] for p in pts]
        ys = [p[1] for p in pts]
        out.append((min(xs), min(ys), max(xs) + 1, max(ys) + 1, len(pts)))
    out.sort(key=lambda b: (b[1], b[0]))
    return out


def compare_scene(name, base_path, new_path, base2_path=None):
    """→ dict(size_ok, inside: [boxes], outside: [boxes], n_inside, n_outside)."""
    bsize, bpx = _load(base_path)
    nsize, npx = _load(new_path)
    if bsize != nsize:
        return {"size_ok": False, "why": f"size {bsize} vs {nsize}", "inside": [], "outside": [],
                "n_inside": 0, "n_outside": bsize[0] * bsize[1]}
    w, h = bsize
    mask_px = None
    if base2_path is not None:
        s2, px2 = _load(base2_path)
        if s2 == bsize:
            mask_px = px2
    cells = declared_cells(name, bpx, bsize)
    inside, outside = [], []
    for i in range(w * h):
        if bpx[i] == npx[i]:
            continue
        x, y = i % w, i // w
        if (mask_px is not None and mask_px[i] != bpx[i]) or _in_cells(x, y, cells):
            inside.append((x, y))
        else:
            outside.append((x, y))
    return {"size_ok": True, "inside": _boxes(inside), "outside": _boxes(outside),
            "n_inside": len(inside), "n_outside": len(outside)}


def _scenes(d):
    return sorted(f for f in os.listdir(d) if f.endswith(".png"))


def run(base, new, base2=None, subset=False, out=sys.stdout):
    """Compare; print the per-scene table; return the exit status."""
    bs, ns = set(_scenes(base)), set(_scenes(new))
    failures = []
    for f in sorted(ns - bs):
        failures.append(f"extra scene in NEW: {f}")
    if not subset:
        for f in sorted(bs - ns):
            failures.append(f"missing scene in NEW: {f}")
    elif not ns:
        failures.append("NEW holds no scene")
    total_out = 0
    for f in sorted(bs & ns):
        b2 = os.path.join(base2, f) if base2 and os.path.exists(os.path.join(base2, f)) else None
        if base2 and b2 is None:
            failures.append(f"scene missing from the mask set: {f}")
        r = compare_scene(f, os.path.join(base, f), os.path.join(new, f), b2)
        if not r["size_ok"]:
            failures.append(f"{f}: {r['why']}")
            continue
        total_out += r["n_outside"]
        line = f"{f[:-4]:<34} inside {r['n_inside']:>6}  outside {r['n_outside']:>6}"
        print(line, file=out)
        for tag, boxes in (("in ", r["inside"]), ("OUT", r["outside"])):
            for (x0, y0, x1, y1, n) in boxes:
                print(f"    {tag} ({x0},{y0})-({x1},{y1}) {n} px", file=out)
        if r["n_outside"]:
            failures.append(f"{f}: {r['n_outside']} px differ outside the mask")
    print(f"compared {len(bs & ns)} scene(s); {total_out} px outside the mask", file=out)
    for msg in failures:
        print(f"FAIL {msg}", file=out)
    return 1 if failures else 0


# ----------------------------------------------------------------------------------------------------------------------
def selftest():
    """Synthetic sets in a temp directory; every rule the judge applies is watched both ways."""
    from PIL import Image, ImageDraw
    import io
    ok, bad = 0, []

    def frame(path, h=480, clock="4:09", body=None, dot=None):
        im = Image.new("L", (PANEL_W, PANEL_H), 0)
        d = ImageDraw.Draw(im)
        d.rectangle([INSET_X, h - STATUS_H - DIV_H, PANEL_W - INSET_X - 1, h - STATUS_H - 1], fill=120)   # the divider
        d.text((556, 12), clock, fill=255)
        d.text((280, h - 20), "769K/s", fill=200)
        if body:
            d.text((40, 100), body, fill=255)
        if dot:
            d.rectangle([dot[0], dot[1], dot[0] + 3, dot[1] + 3], fill=255)
        im.save(path)

    def case(name, want_status, build, subset=False, use_mask=True, expect_in=None):
        nonlocal ok
        root = tempfile.mkdtemp(prefix="snapdiff-st-")
        b, b2, n = (os.path.join(root, s) for s in ("b", "b2", "n"))
        for p in (b, b2, n):
            os.makedirs(p)
        build(b, b2, n)
        buf = io.StringIO()
        st = run(b, n, b2 if use_mask else None, subset, out=buf)
        text = buf.getvalue()
        shutil.rmtree(root)
        good = st == want_status and (expect_in is None or expect_in in text)
        if good:
            ok += 1
        else:
            bad.append(f"{name}: status {st} (want {want_status}); output:\n{text}")

    def same(b, b2, n):
        for d in (b, b2, n):
            frame(os.path.join(d, "01-main.png"))
    case("identical sets pass", 0, same)

    def clock_moved(b, b2, n):
        frame(os.path.join(b, "01-main.png")); frame(os.path.join(b2, "01-main.png"))
        frame(os.path.join(n, "01-main.png"), clock="4:17")
    case("the clock cell is live", 0, clock_moved, expect_in="inside")

    def readout_at_288(b, b2, n):
        frame(os.path.join(b, "36-x.png"), h=288); frame(os.path.join(b2, "36-x.png"), h=288)
        im = Image.open(os.path.join(b, "36-x.png")); d = ImageDraw.Draw(im)
        d.rectangle([300, 270, 340, 280], fill=90); im.save(os.path.join(n, "36-x.png"))
    case("the status bar's live cells follow the scene's height", 0, readout_at_288)

    def same_spot_at_480_in_288(b, b2, n):
        frame(os.path.join(b, "36-x.png"), h=288); frame(os.path.join(b2, "36-x.png"), h=288)
        im = Image.open(os.path.join(b, "36-x.png")); d = ImageDraw.Draw(im)
        d.rectangle([300, 460, 340, 470], fill=90); im.save(os.path.join(n, "36-x.png"))
    case("a 480 status cell is not masked in a 288 scene", 1, same_spot_at_480_in_288, expect_in="OUT (300,460)")

    def body_changed(b, b2, n):
        frame(os.path.join(b, "03-book.png"), body="the text"); frame(os.path.join(b2, "03-book.png"), body="the text")
        frame(os.path.join(n, "03-book.png"), body="the tExt")
    case("a content change outside the mask fails and is boxed", 1, body_changed, expect_in="OUT (")

    def live_between_bases(b, b2, n):
        frame(os.path.join(b, "62-a.png"), dot=(200, 200)); frame(os.path.join(b2, "62-a.png"), dot=(204, 200))
        frame(os.path.join(n, "62-a.png"), dot=(208, 200))
    case("new ground beyond the base pair's difference is outside", 1, live_between_bases, expect_in="OUT (208,200)")

    def live_between_bases_covered(b, b2, n):
        frame(os.path.join(b, "62-a.png"), dot=(200, 200)); frame(os.path.join(b2, "62-a.png"), dot=(201, 200))
        frame(os.path.join(n, "62-a.png"), dot=(201, 200))
    case("the base pair's difference masks the same pixels", 0, live_between_bases_covered)

    def mask_off(b, b2, n):
        frame(os.path.join(b, "62-a.png"), dot=(200, 200)); frame(os.path.join(b2, "62-a.png"), dot=(201, 200))
        frame(os.path.join(n, "62-a.png"), dot=(201, 200))
    case("without --mask the same change is outside", 1, mask_off, use_mask=False)

    def missing(b, b2, n):
        for d in (b, b2):
            frame(os.path.join(d, "01-main.png")); frame(os.path.join(d, "02-lib.png"))
        frame(os.path.join(n, "01-main.png"))
    case("a missing scene fails", 1, missing, expect_in="missing scene in NEW: 02-lib.png")

    def extra(b, b2, n):
        for d in (b, b2):
            frame(os.path.join(d, "01-main.png"))
        frame(os.path.join(n, "01-main.png")); frame(os.path.join(n, "99-new.png"))
    case("an extra scene fails", 1, extra, expect_in="extra scene in NEW: 99-new.png")

    def subset_ok(b, b2, n):
        for d in (b, b2):
            frame(os.path.join(d, "01-main.png")); frame(os.path.join(d, "02-lib.png"))
        frame(os.path.join(n, "02-lib.png"))
    case("--subset compares only NEW's scenes", 0, subset_ok, subset=True)
    case("--subset still fails an extra scene", 1, extra, subset=True)

    def visualizer(b, b2, n):
        frame(os.path.join(b, "38-mode.png")); frame(os.path.join(b2, "38-mode.png"))
        frame(os.path.join(n, "38-mode.png"), body="bars moved")
    case("Music Mode's visualizer scenes are live whole", 0, visualizer)

    def silent(b, b2, n):
        for d in (b, b2):
            Image.new("L", (PANEL_W, PANEL_H), 0).save(os.path.join(d, "10-silent.png"))
        im = Image.new("L", (PANEL_W, PANEL_H), 0); ImageDraw.Draw(im).text((590, 10), "4:17", fill=255)
        im.save(os.path.join(n, "10-silent.png"))
    case("silent mode's clock is live", 0, silent)

    def feed_age(b, b2, n):
        for d in (b, b2):
            frame(os.path.join(d, "67-feed-article-288.png"), h=288)
        frame(os.path.join(n, "67-feed-article-288.png"), h=288, dot=(200, 125))
    case("a Feed article's age is live", 0, feed_age, expect_in="inside")

    def comic_age(b, b2, n):
        for d in (b, b2):
            frame(os.path.join(d, "64-feed-comic.png"))
        frame(os.path.join(n, "64-feed-comic.png"), dot=(100, 80))
    case("a Feed comic's age is live", 0, comic_age, expect_in="inside")

    def notice_clock(b, b2, n):
        for d in (b, b2):
            frame(os.path.join(d, "25-torrents-done-notice.png"))
        frame(os.path.join(n, "25-torrents-done-notice.png"), dot=(412, 196))
    case("a done notice's clock is live", 0, notice_clock, expect_in="inside")

    def notice_body(b, b2, n):
        for d in (b, b2):
            frame(os.path.join(d, "25-torrents-done-notice.png"))
        frame(os.path.join(n, "25-torrents-done-notice.png"), dot=(412, 230))
    case("a done notice's body is not live", 1, notice_body, expect_in="OUT (412,230)")

    def alert_clock(b, b2, n):
        for d in (b, b2):
            frame(os.path.join(d, "09f-tmux-alert-main.png"))
        frame(os.path.join(n, "09f-tmux-alert-main.png"), dot=(412, 208))
    case("a two-line box's clock is live", 0, alert_clock, expect_in="inside")

    def alert_body(b, b2, n):
        for d in (b, b2):
            frame(os.path.join(d, "78-feed-notice-main.png"))
        frame(os.path.join(n, "78-feed-notice-main.png"), dot=(412, 230))
    case("a two-line box's body is not live", 1, alert_body, expect_in="OUT (412,230)")

    def alert_clock_elsewhere(b, b2, n):
        for d in (b, b2):
            frame(os.path.join(d, "09g-tmux-waiting.png"))
        frame(os.path.join(n, "09g-tmux-waiting.png"), dot=(412, 208))
    case("the two-line cell is not live in another scene", 1, alert_clock_elsewhere, expect_in="OUT (412,208)")

    def size_change(b, b2, n):
        for d in (b, b2):
            frame(os.path.join(d, "01-main.png"))
        Image.new("L", (320, 240), 0).save(os.path.join(n, "01-main.png"))
    case("a scene of another size fails", 1, size_change, expect_in="size")

    total = ok + len(bad)
    for msg in bad:
        print(f"SELFTEST FAIL {msg}")
    print(f"snapdiff selftest: {ok} of {total} cases pass")
    return 0 if not bad else 1


def main(argv):
    if argv[1:] == ["--selftest"]:
        return selftest()
    args, base2, subset = [], None, False
    it = iter(argv[1:])
    for a in it:
        if a == "--mask":
            base2 = next(it, None)
            if base2 is None:
                print("--mask needs a directory", file=sys.stderr)
                return 2
        elif a == "--subset":
            subset = True
        elif a.startswith("-"):
            print(f"unknown option {a}\n{__doc__}", file=sys.stderr)
            return 2
        else:
            args.append(a)
    if len(args) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    for d in args + ([base2] if base2 else []):
        if not os.path.isdir(d):
            print(f"not a directory: {d}", file=sys.stderr)
            return 2
    return run(args[0], args[1], base2, subset)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
