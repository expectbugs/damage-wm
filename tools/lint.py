#!/usr/bin/env python3
"""Damage layout linter — the build gate described in DESIGN.md §9.2b.

Every silent failure mode on this hardware becomes an error HERE, because the glasses
will never tell you: they draw a tofu box, skip a delta, or reject a rect, and say
nothing. This file is rule 1 of that gate; the geometry and budget rules land beside it
as the compositor is built.

    SYM001  a drawn string contains a codepoint the target face cannot render
    SYM002  a KOTLIN string literal contains one (the shell's own drawing code)

Usage:
    tools/lint.py [PATH ...]              lint files/dirs (default: the whole repo)
    tools/lint.py --faces                 show glyph coverage for the locked faces
    tools/lint.py --codepoint ▸ ⚙         check specific characters against every face

Exit status is 1 if any rule fails, so it works as a pre-commit or CI gate.
"""
from __future__ import annotations
import argparse, ast, subprocess, sys, unicodedata
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import geometry as G

# ---------------------------------------------------------------------------
# The locked typeface assignments (DESIGN.md §Type). A symbol is only safe if
# EVERY face that could draw it has the glyph — content faces vary per window,
# so a string in shared code must survive all of them.
LOCKED_FACES = {
    "Clear Sans":     "system face — all chrome, everywhere, plus Main",
    "Fira Sans":      "Mail and other dense lists",
    "Alegreya":       "Reader and long-form",
    "JetBrains Mono": "Terminal and column-aligned views",
}
# Latin-1 is assumed present in any text face; only look past it. Control characters are
# formatting, not glyphs, and are never drawn.
ALWAYS_OK = set(range(0x20, 0x7F)) | set(range(0xA0, 0x100))
PRAGMA = "lint:allow-symbols"


def _pragma_at(lines: list[str], lineno: int) -> bool:
    """The pragma on the string's own line, or on the line above it.

    A one-line escape hatch forces the reason onto the end of a long literal,
    where it is unreadable; the line above is where an explanation actually
    fits, and every current use of it is a paragraph."""
    for n in (lineno, lineno - 1):
        if 1 <= n <= len(lines) and PRAGMA in lines[n - 1]:
            return True
    return False

# Only strings that actually reach a text-drawing call can produce tofu. Checking every
# literal in the repo instead floods the report with log lines and docstrings and trains
# people to ignore it, which is worse than not having the rule.
DRAW_FUNCS = {"text", "rtext", "ctext", "multiline_text", "draw_text", "draw_string"}


def _font_file(family: str, bold: bool = False) -> str | None:
    style = "Bold" if bold else "Regular"
    out = subprocess.run(["fc-match", "-f", "%{family[0]}|%{file}", f"{family}:style={style}"],
                         capture_output=True, text=True).stdout
    if "|" not in out:
        return None
    got, path = out.split("|", 1)
    # fc-match substitutes silently when a family is missing; reject the substitute.
    return path if got.split()[0].lower() == family.split()[0].lower() else None


def load_coverage() -> dict[str, set[int]]:
    """family -> set of codepoints, read from the real cmap.

    PIL's getmask().getbbox() is NOT a coverage test — a tofu box has a bbox too. That
    false negative is exactly how U+25B8 reached three separate renders.
    """
    try:
        from fontTools.ttLib import TTFont
    except ImportError:
        sys.exit("lint: fontTools is required (pip install fonttools)")
    cov: dict[str, set[int]] = {}
    for fam in LOCKED_FACES:
        path = _font_file(fam)
        if not path:
            print(f"lint: WARNING — face {fam!r} is not installed; skipping", file=sys.stderr)
            continue
        cps: set[int] = set()
        for style_bold in (False, True):
            p = _font_file(fam, style_bold) or path
            try:
                font = TTFont(p, fontNumber=0, lazy=True)
                for table in font["cmap"].tables:
                    cps |= set(table.cmap.keys())
            except Exception as exc:                       # loud, never silent
                print(f"lint: WARNING — could not read {p}: {exc}", file=sys.stderr)
        cov[fam] = cps
    if not cov:
        sys.exit("lint: no locked faces installed — cannot verify symbol coverage")
    return cov


def _literal_parts(node):
    """String constants inside an expression, including f-string literal segments."""
    for sub in ast.walk(node):
        if isinstance(sub, ast.Constant) and isinstance(sub.value, str):
            yield sub.lineno, sub.value


def _docstring_nodes(tree: ast.AST) -> set[int]:
    out = set()
    for node in ast.walk(tree):
        if isinstance(node, (ast.Module, ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            body = getattr(node, "body", None)
            if body and isinstance(body[0], ast.Expr) and isinstance(body[0].value, ast.Constant) \
               and isinstance(body[0].value.value, str):
                out.add(id(body[0].value))
    return out


def _string_literals(tree: ast.AST, every: bool):
    """Strings to check: by default only those passed to a text-drawing call."""
    docs = _docstring_nodes(tree)
    if every:
        for node in ast.walk(tree):
            if isinstance(node, ast.Constant) and isinstance(node.value, str) \
               and id(node) not in docs:
                yield node.lineno, node.value
        return
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        fn = node.func
        name = fn.attr if isinstance(fn, ast.Attribute) else getattr(fn, "id", "")
        if name not in DRAW_FUNCS:
            continue
        for arg in list(node.args) + [k.value for k in node.keywords]:
            for lineno, val in _literal_parts(arg):
                yield lineno, val


def check_file(path: Path, cov: dict[str, set[int]], every: bool = False) -> list[str]:
    src = path.read_text(encoding="utf-8", errors="replace")
    lines = src.splitlines()
    try:
        tree = ast.parse(src, filename=str(path))
    except SyntaxError as exc:
        return [f"{path}:{exc.lineno}: SYN000 could not parse ({exc.msg})"]

    findings, seen = [], set()
    for lineno, text in _string_literals(tree, every):
        if _pragma_at(lines, lineno):
            continue
        for ch in text:
            cp = ord(ch)
            if cp in ALWAYS_OK or cp < 0x20 or 0x7F <= cp < 0xA0 or (lineno, cp) in seen:
                continue
            missing = sorted(f for f, cps in cov.items() if cp not in cps)
            if missing:
                seen.add((lineno, cp))
                name = unicodedata.name(ch, "?")
                findings.append(
                    f"{path}:{lineno}: SYM001 {ch!r} U+{cp:04X} ({name}) "
                    f"is missing from {len(missing)}/{len(cov)} locked faces: "
                    f"{', '.join(missing)}\n"
                    f"    -> draw it as a shape; only plain text goes through the font "
                    f"(DESIGN.md §Type)")
    return findings


# ---------------------------------------------------------------------------
# SYM002 — the same rule, applied to the KOTLIN that actually ships.
#
# Every pixel the glasses show is now drawn by Kotlin, and a glyph the face
# lacks is SILENT TOFU: `Draw.dynamic` substitutes a visible '?' where it is
# used, and where it is not the box just appears. SYM001 has always covered
# `design/render_shots.py`; this covers the shell.
#
# Scope is deliberately every string literal in `core/`, `desktop/` and
# `phone/` rather than only the ones reaching a draw call: Kotlin has no single
# drawing function to key on (each window has its own helpers), and a string
# that starts as a log line reaches the glass the moment someone hands it to
# `setNotice`. `lint:allow-symbols` on the line is the escape hatch.
KOTLIN_ROOTS = ("core/src/main", "desktop/src/main", "phone/src/main")


def _kotlin_strings(src: str):
    """(line, text) for every string literal, comments and KDoc skipped.

    A character walk rather than a regex: Kotlin comments contain apostrophes
    and quotes, and a regex over the raw text reports half the prose in this
    repo as string content.

    CHAR literals are skipped whole. `'"'` is legal Kotlin (Journal.kt writes
    three of them) and without this the quote inside it opens a string, which
    flips the string/code parity for the whole rest of the file — code read as
    literals and literals read as code, i.e. both false positives and false
    negatives in a build gate (review pass 3, 2026-09-04)."""
    out, i, line, n = [], 0, 1, len(src)
    while i < n:
        c = src[i]
        if c == "\n":
            line += 1; i += 1; continue
        if c == "'":
            j = i + 1
            if j < n and src[j] == "\\":
                j += 1
            j += 1                                   # the character itself
            if j < n and src[j] == "'":
                i = j + 1
                continue
            # not a char literal after all (it cannot be anything else outside
            # a string or comment, but never consume past the line if it is)
            i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            i += 2
            while i + 1 < n and not (src[i] == "*" and src[i + 1] == "/"):
                if src[i] == "\n":
                    line += 1
                i += 1
            i += 2
            continue
        if src.startswith('"""', i):
            start, i = line, i + 3
            buf = []
            while i < n and not src.startswith('"""', i):
                if src[i] == "\n":
                    line += 1
                buf.append(src[i]); i += 1
            i += 3
            out.append((start, "".join(buf)))
            continue
        if c == '"':
            start, i = line, i + 1
            buf = []
            while i < n and src[i] != '"':
                if src[i] == "\\" and i + 1 < n:
                    buf.append(src[i + 1]); i += 2; continue
                if src[i] == "\n":
                    line += 1
                    break
                buf.append(src[i]); i += 1
            i += 1
            out.append((start, "".join(buf)))
            continue
        i += 1
    return out


def check_kotlin(path: Path, cov: dict[str, set[int]]) -> list[str]:
    src = path.read_text(encoding="utf-8", errors="replace")
    lines = src.splitlines()
    findings, seen = [], set()
    for lineno, text in _kotlin_strings(src):
        if _pragma_at(lines, lineno):
            continue
        for ch in text:
            cp = ord(ch)
            if cp in ALWAYS_OK or cp < 0x20 or 0x7F <= cp < 0xA0 or (lineno, cp) in seen:
                continue
            missing = sorted(f for f, cps in cov.items() if cp not in cps)
            if missing:
                seen.add((lineno, cp))
                name = unicodedata.name(ch, "?")
                findings.append(
                    f"{path}:{lineno}: SYM002 {ch!r} U+{cp:04X} ({name}) "
                    f"is missing from {len(missing)}/{len(cov)} locked faces: "
                    f"{', '.join(missing)}\n"
                    f"    -> draw it as a shape, or use an ASCII form; a glyph the face "
                    f"lacks is silent tofu on the glass (DESIGN.md §Type)")
    return findings


# ---------------------------------------------------------------------------
# GEO — validate the geometry the spec DECLARES. DESIGN.md §2.3's cell table is the
# current source of truth for the shell's layout, and it is machine-readable. Both real
# layout bugs so far (the 96/128/96 ribbon, the 250 px notification) were errors in that
# table, found by eye. This finds them by build.
def check_design_table(design: Path) -> list[str]:
    if not design.exists():
        return [f"{design}: GEO000 spec not found; cannot validate declared geometry"]
    findings, cells, rows = [], {}, 0
    for lineno, line in enumerate(design.read_text(encoding="utf-8").splitlines(), 1):
        parts = [c.strip() for c in line.split("|")[1:-1]]
        if len(parts) != 6:
            continue
        try:
            x, w, y, h = (int(parts[2]), int(parts[3]), int(parts[4]), int(parts[5]))
        except ValueError:
            continue
        rows += 1
        name = (parts[1] or parts[0]).strip("* `") or f"row{lineno}"
        rect = G.Rect(x, y, w, h)
        cells[f"{name}@{lineno}"] = rect
        findings += [f"{design}:{lineno}: {e}" for e in G.check_rect(rect, what=name)]
    if not rows:
        findings.append(f"{design}: GEO000 no geometry rows parsed — has the cell table moved?")
    for a, b in ((a, b) for i, a in enumerate(cells) for b in list(cells)[i + 1:]):
        ra, rb = cells[a], cells[b]
        if ra.y == rb.y and ra.h == rb.h and ra.overlaps(rb):
            findings.append(f"{design}: GEO007 declared cells {a} {ra} and {b} {rb} overlap")
    return findings


# ---------------------------------------------------------------------------
# BUD005 — ink coverage, measured from the rendered surface (DESIGN.md §4.2).
INK_BUDGETS = {"main-active": 0.15, "main-resting": 0.05, "window-list": 0.15,
               "window-doc": 0.25, "notification": 0.25, "emergency": 0.25,
               "switcher": 0.25, "silent": 0.02, "silent-notif": 0.02}


def check_ink_budgets(shots: Path) -> list[str]:
    try:
        from PIL import Image
    except ImportError:
        return [f"{shots}: BUD000 Pillow unavailable; cannot measure ink"]
    findings = []
    for name, budget in sorted(INK_BUDGETS.items()):
        png = shots / f"{name}.png"
        if not png.exists():
            findings.append(f"{shots}: BUD006 no render for surface {name!r} — "
                            f"an unrendered surface is an unchecked one")
            continue
        im = Image.open(png).convert("L")
        hist = im.histogram()                       # faster and not deprecated
        lit, total = sum(hist[9:]), im.width * im.height
        findings += [f"{png}: {e}" for e in G.check_ink(lit, total, budget, surface=name)]
    return findings


def check_doc_matches_renders(design: Path, shots: Path) -> list[str]:
    """BUD007 — DESIGN.md's stated measured ink must match the actual renders.

    Documentation disagreeing with reality is this project's recurring failure mode; every
    correction so far came from prose that had drifted from working code. This closes that
    loop for the one table that is claimed as measured.
    """
    import re
    try:
        from PIL import Image
    except ImportError:
        return []
    if not design.exists():
        return []
    label_to_shot = {"Main, active": "main-active", "Main, resting": "main-resting",
                     "notification box": "notification", "emergency banner": "emergency",
                     "switcher": "switcher", "silent mode": "silent",
                     "window, list mode": "window-list", "window, document mode": "window-doc"}
    findings, checked = [], 0
    for m in re.finditer(r"\| ([A-Za-z, ]+?) \| \u2264 ?([\d.]+) ?% \| \*\*([\d.]+) ?%\*\*", 
                         design.read_text(encoding="utf-8")):
        label, _budget, claimed = m.group(1).strip(), float(m.group(2)), float(m.group(3))
        shot = label_to_shot.get(label)
        if not shot:
            continue
        png = shots / f"{shot}.png"
        if not png.exists():
            continue
        im = Image.open(png).convert("L")
        actual = sum(im.histogram()[9:]) / (im.width * im.height) * 100
        checked += 1
        if abs(actual - claimed) > 0.15:
            findings.append(
                f"{design}: BUD007 the spec claims surface {label!r} measures {claimed:.1f}% ink "
                f"but {png.name} actually measures {actual:.1f}% — regenerate the shots or fix "
                f"the table; a stale 'measured' number is worse than no number")
    if checked == 0:
        findings.append(f"{design}: BUD007 parsed no measured-ink rows — has the table moved?")
    return findings


# ---------------------------------------------------------------------------
def selftest() -> int:
    """Prove each rule fires. A gate nobody has seen fail is a gate nobody trusts."""
    cases = [
        ("GEO001 x", G.check_rect(G.Rect(250, 100, 100, 50)), "GEO001"),
        ("GEO001 y", G.check_rect(G.Rect(100, 33, 100, 50)), "GEO001"),
        ("GEO002 bounds", G.check_rect(G.Rect(600, 400, 100, 100)), "GEO002"),
        ("GEO003 degenerate", G.check_rect(G.Rect(0, 0, 0, 10)), "GEO003"),
        ("GEO004 stereo size", G.check_stereo_pair(G.Rect(0, 34, 608, 416),
                                                   G.Rect(32, 34, 604, 416)), "GEO004"),
        ("GEO005 vertical disparity", G.check_stereo_pair(G.Rect(0, 34, 600, 416),
                                                          G.Rect(0, 36, 600, 416)), "GEO005"),
        ("GEO008 bar gap", G.check_cells({"a": G.Rect(0, 0, 100, 32)},
                                          span=G.Rect(0, 0, 640, 32)), "GEO008"),
        ("BUD001 rect budget", G.check_batch([G.Rect(0, 34, 8, 8)] * 6, window=3), "BUD001"),
        ("BUD002 batch size", G.check_batch([], payload=G.MODE8_MAX + 1), "BUD002"),
        ("BUD003 layout wall", G.check_frame_size(1200, kind="layout"), "BUD003"),
        ("BUD004 fragment", G.check_frame_size(4096, kind="image"), "BUD004"),
        ("BUD005 ink", G.check_ink(50, 100, 0.15, surface="x"), "BUD005"),
    ]
    t = G.FidTracker()
    cases.append(("FID004 delta before keyframe", t.delta(1), "FID004"))
    t2 = G.FidTracker(); t2.keyframe(); t2.delta(1)
    cases.append(("FID002 gap", t2.delta(5), "FID002"))
    t3 = G.FidTracker(); t3.keyframe(); t3.delta(7)
    cases.append(("FID001 reuse", t3.delta(7), "FID001"))
    t4 = G.FidTracker(); t4.keyframe()
    cases.append(("FID003 range", t4.delta(0xFFFF), "FID003"))

    # SYM002 needs a file and the real cmaps; the interesting half is the
    # SCANNER — a regex over Kotlin reports half this repo's prose as string
    # content, so the walk that skips comments is what is proved here.
    import tempfile
    kt = ('/* a block comment with \u2508 in it */\n'
          '// a line comment with \u276f in it\n'
          'val a = "safe text"\n'
          'val b = "tofu \u2508 here"\n'
          # a CHAR literal holding a quote, with a real literal after it ON
          # THE SAME LINE: without the walker's char branch the quote inside
          # it opens a string, the parity flips, and line 5's tofu is read as
          # CODE and never reported (Journal.kt writes three of these)
          "val q = '\"'; val c = \"and \u2508 after a char literal\"\n")
    with tempfile.NamedTemporaryFile("w", suffix=".kt", delete=False) as f:
        f.write(kt)
        tmp = Path(f.name)
    try:
        found = check_kotlin(tmp, load_coverage())
        cases.append(("SYM002 kotlin literal", found, "SYM002"))
        cases.append(("SYM002 skips comments",
                      ["ok"] if any(":4:" in g for g in found) and
                      not any(":1:" in g or ":2:" in g for g in found) else [], "ok"))
        # the char-literal branch: line 5's literal must still be scanned
        cases.append(("SYM002 past a char literal",
                      ["ok"] if any(":5:" in g for g in found) else [], "ok"))
    finally:
        tmp.unlink()

    ok = True
    for label, got, expect in cases:
        hit = any(expect in g for g in got)
        print(f"  {'PASS' if hit else 'FAIL'}  {label:30s} -> {got[0][:74] if got else '(silent)'}")
        ok &= hit
    good = G.check_rect(G.Rect(16, 34, 608, 416)) + G.check_batch(
        [G.Rect(0, 0, 640, 32), G.Rect(0, 452, 640, 28)], window=3)
    print(f"  {'PASS' if not good else 'FAIL'}  {'valid geometry stays silent':30s} -> "
          f"{good or '(silent)'}")
    ok &= not good
    print(f"\nselftest: {'all rules fire' if ok else 'A RULE DID NOT FIRE'}")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("paths", nargs="*", default=None)
    ap.add_argument("--faces", action="store_true", help="show coverage for the locked faces")
    ap.add_argument("--codepoint", nargs="+", metavar="CHAR", help="check specific characters")
    ap.add_argument("--all-strings", action="store_true",
                    help="check every literal, not just those passed to a drawing call")
    ap.add_argument("--selftest", action="store_true", help="prove every rule fires")
    ap.add_argument("--no-geometry", action="store_true", help="skip the GEO/BUD passes")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    cov = load_coverage()

    if args.faces:
        print(f"{'locked face':16s} {'glyphs':>8s}  role")
        for fam, cps in cov.items():
            print(f"{fam:16s} {len(cps):8,d}  {LOCKED_FACES[fam]}")
        return 0

    if args.codepoint:
        bad = 0
        for item in args.codepoint:
            for ch in item:
                cp = ord(ch)
                missing = sorted(f for f, c in cov.items() if cp not in c)
                mark = "OK  " if not missing else "FAIL"
                bad += bool(missing)
                print(f"{mark} {ch!r} U+{cp:04X} {unicodedata.name(ch, '?')}"
                      + (f"  missing from: {', '.join(missing)}" if missing else ""))
        return 1 if bad else 0

    roots = [Path(p) for p in (args.paths or [Path(__file__).resolve().parent.parent])]
    files: list[Path] = []
    for r in roots:
        files.extend(sorted(r.rglob("*.py")) if r.is_dir() else [r])
    # reference/ is other people's code; venv/ is vendored; research/ renders mockups with
    # a pinned face and is measurement code, not shell code. Pass a path explicitly to
    # lint any of them anyway.
    if not args.paths:
        skip = ("reference/", "/venv/", "research/")
        files = [f for f in files if not any(k in str(f) for k in skip)]

    findings = [f for path in files for f in check_file(path, cov, args.all_strings)]
    kt: list[Path] = []
    if not args.paths:
        root = Path(__file__).resolve().parent.parent
        for r in KOTLIN_ROOTS:
            kt.extend(sorted((root / r).rglob("*.kt")))
    else:
        for r in roots:
            kt.extend(sorted(r.rglob("*.kt")) if r.is_dir() else ([r] if r.suffix == ".kt" else []))
    findings += [f for path in kt for f in check_kotlin(path, cov)]
    if not args.paths and not args.no_geometry:
        root = Path(__file__).resolve().parent.parent
        findings += check_design_table(root / "DESIGN.md")
        findings += check_ink_budgets(root / "design" / "shots")
        findings += check_doc_matches_renders(root / "DESIGN.md", root / "design" / "shots")
    for f in findings:
        print(f)
    scope = "all string literals" if args.all_strings else f"strings passed to {sorted(DRAW_FUNCS)}"
    print(f"lint: {len(files)} python + {len(kt)} kotlin file(s), {len(findings)} finding(s)   "
          f"[scope: {scope}, and every kotlin literal]")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
