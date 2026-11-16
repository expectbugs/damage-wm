#!/usr/bin/env python3
"""The first-flush judge (build S1-2 spec §6 "J0", §8): compare the gestures of two `desktop --snapshot` runs by bytes.

Context for the reader: Damage is Adam's own window manager for his own glasses; `desktop --snapshot DIR` drives the
real shell through scripted scenes and writes, beside the pictures, the shell's journal (`DIR/journal.jsonl`; the
phone arrangement's in `DIR/phone/`). A gesture is priced by its FIRST flush (`CLAUDE.md`, "Latency standards"): this
tool says, scene by scene, how many bytes the first flush of the gesture before that scene carried, and fails when a
new run's is larger than the base's. `tools/snapdiff.py` judges the pictures; this judges what reaching them cost.

    python3 tools/flushdiff.py BASE NEW [--mask BASE2]
    python3 tools/flushdiff.py --selftest

BASE, NEW and BASE2 are snapshot directories (their `journal.jsonl` is read) or journal files. For every scene mark
(a `note` of kind `scene`, written by `Snapshot.save` once the scene's picture is taken) the measure is the last flush
since the previous mark that a ring event started (`"in":1` on its `submit`; atlas and staged-record writes are not
gestures), priced by its `done` line's `bytes`. A scene reached with no gesture since the previous mark (a deep link,
a notice, a timer) has no measure and is listed with "-".
  - a scene whose NEW measure is larger than its allowance fails ("grew"); the allowance is BASE's measure, or with
    --mask the larger of BASE's and BASE2's (two renders of one tree: whatever differs between them is timing, not the
    change under test);
  - a scene with a measure in NEW and none in BASE fails (a first flush where there was none is growth from nothing);
  - a scene with a measure in BASE and none in NEW is listed ("no gesture flush"), not failed: the pictures judge it;
  - a scene present in one journal and not the other fails, as does a measured flush with no `done` line, a scene
    marked twice, or a journal with no scene mark at all.

Exit status: 0 = no first flush grew and no scene is missing or extra; 1 = otherwise; 2 = bad arguments.
"""
from __future__ import annotations

import json
import os
import shutil
import sys
import tempfile

NOT_GESTURES = ("ATLAS", "STAGE")


class JournalError(Exception):
    pass


def _journal_path(p):
    return os.path.join(p, "journal.jsonl") if os.path.isdir(p) else p


def measures(path):
    """→ (ordered list of scene names, {scene: (bytes, flush id, label) or None}). Raises JournalError."""
    path = _journal_path(path)
    if not os.path.isfile(path):
        raise JournalError(f"no journal at {path}")
    done, order, marks = {}, [], {}
    last = None                      # the last gesture's first flush since the previous mark: (id, label)
    with open(path, encoding="utf-8") as f:
        for no, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                o = json.loads(line)
            except ValueError as e:
                raise JournalError(f"{path}:{no}: not a JSON line ({e})")
            ev = o.get("ev")
            if ev == "done":
                done[o.get("id")] = o
            elif ev == "submit":
                if o.get("in") == 1 and o.get("label") not in NOT_GESTURES:
                    last = (o.get("id"), o.get("label", "?"))
            elif ev == "note" and o.get("kind") == "scene":
                name = o.get("detail", "")
                if name in marks:
                    raise JournalError(f"{path}:{no}: scene '{name}' marked twice (two runs in one journal?)")
                order.append(name)
                marks[name] = last
                last = None
    if not order:
        raise JournalError(f"{path}: no scene mark (a journal from before build S1-2 J0, or not a snapshot's)")
    out = {}
    for name in order:
        m = marks[name]
        if m is None:
            out[name] = None
            continue
        d = done.get(m[0])
        if d is None:
            raise JournalError(f"{path}: flush {m[0]} before scene '{name}' has no done line")
        out[name] = (int(d.get("bytes", 0)), m[0], m[1])
    return order, out


def run(base, new, base2=None, out=sys.stdout):
    """Compare; print the per-scene table; return the exit status."""
    try:
        border, bm = measures(base)
        norder, nm = measures(new)
        m2 = measures(base2)[1] if base2 else None
    except JournalError as e:
        print(f"FAIL {e}", file=out)
        return 1
    failures = []
    for s in norder:
        if s not in bm:
            failures.append(f"extra scene in NEW: {s}")
    for s in border:
        if s not in nm:
            failures.append(f"missing scene in NEW: {s}")
    grew = 0
    for s in border:
        if s not in nm:
            continue
        b, n = bm[s], nm[s]
        b2 = m2.get(s) if m2 is not None else None
        if m2 is not None and s not in m2:
            failures.append(f"scene missing from the mask journal: {s}")
        allow = max(x[0] for x in (b, b2) if x is not None) if (b or b2) else None
        bt = "-" if b is None else str(b[0])
        nt = "-" if n is None else str(n[0])
        at = "-" if allow is None else str(allow)
        note = ""
        if n is not None and allow is None:
            note = "GREW (no first flush in the base)"
            failures.append(f"{s}: a gesture flush of {n[0]} B where the base has none")
            grew += 1
        elif n is not None and n[0] > allow:
            note = f"GREW +{n[0] - allow} B"
            failures.append(f"{s}: first flush {n[0]} B, allowance {allow} B")
            grew += 1
        elif n is None and b is not None:
            note = "no gesture flush in NEW"
        label = n[2] if n is not None else (b[2] if b is not None else "")
        print(f"{s:<34} base {bt:>6}  allow {at:>6}  new {nt:>6}  {label:<16} {note}", file=out)
    print(f"compared {len(set(border) & set(nm))} scene(s); {grew} first flush(es) grew", file=out)
    for msg in failures:
        print(f"FAIL {msg}", file=out)
    return 1 if failures else 0


# ----------------------------------------------------------------------------------------------------------------------
def selftest():
    """Synthetic journals in a temp directory; every rule the judge applies is watched both ways."""
    import io
    ok, bad = 0, []
    fid = [0]

    def sub(label="WINDOW", inp=1, nbytes=100):
        fid[0] += 1
        i = fid[0]
        return [{"ev": "submit", "id": i, "label": label, "in": inp, "ops": []},
                {"ev": "done", "id": i, "ok": True, "bytes": nbytes}]

    def mark(name):
        return [{"ev": "note", "kind": "scene", "detail": name}]

    def write(path, lines):
        with open(path, "w", encoding="utf-8") as f:
            for o in lines:
                f.write(json.dumps(o) + "\n")

    def case(name, want_status, base, new, base2=None, expect_in=None, as_dir=False):
        nonlocal ok
        root = tempfile.mkdtemp(prefix="flushdiff-st-")
        paths = []
        for tag, lines in (("b", base), ("n", new), ("b2", base2)):
            if lines is None:
                paths.append(None)
                continue
            if as_dir:
                d = os.path.join(root, tag)
                os.makedirs(d)
                write(os.path.join(d, "journal.jsonl"), lines)
                paths.append(d)
            else:
                p = os.path.join(root, tag + ".jsonl")
                write(p, lines)
                paths.append(p)
        buf = io.StringIO()
        st = run(paths[0], paths[1], paths[2], out=buf)
        text = buf.getvalue()
        shutil.rmtree(root)
        good = st == want_status and (expect_in is None or expect_in in text)
        if good:
            ok += 1
        else:
            bad.append(f"{name}: status {st} (want {want_status}); output:\n{text}")

    def scene(nbytes, name="13-files-menu", lead=()):
        return list(lead) + sub(nbytes=nbytes) + mark(name)

    case("identical journals pass", 0, scene(300), scene(300))
    case("a directory argument reads its journal.jsonl", 0, scene(300), scene(300), as_dir=True)
    case("a first flush that grew fails", 1, scene(300), scene(301), expect_in="GREW +1 B")
    case("a smaller first flush passes", 0, scene(300), scene(120))
    case("the LAST gesture before the mark is the one priced", 1,
         sub(nbytes=900) + sub(nbytes=200) + mark("04-x"), sub(nbytes=200) + sub(nbytes=250) + mark("04-x"),
         expect_in="GREW +50 B")
    case("a flush no ring event started is not a gesture", 0,
         scene(300), sub(nbytes=300) + sub(inp=0, nbytes=5000) + mark("13-files-menu"))
    case("atlas and staged-record writes are not gestures", 0,
         scene(300), sub(nbytes=300) + sub(label="ATLAS", nbytes=9000) + sub(label="STAGE", nbytes=9000)
         + mark("13-files-menu"))
    case("the mark resets: a gesture before the previous scene is not this one's", 0,
         sub(nbytes=300) + mark("a") + mark("b"), sub(nbytes=300) + mark("a") + mark("b"), expect_in="-")
    case("a gesture flush where the base had none fails", 1,
         mark("a") + sub(inp=0) + mark("b"), mark("a") + sub(nbytes=40) + mark("b"), expect_in="base has none")
    case("a gesture flush gone from NEW is listed, not failed", 0,
         scene(300), sub(inp=0) + mark("13-files-menu"), expect_in="no gesture flush in NEW")
    case("--mask allows the larger of the two bases", 0, scene(300), scene(340), base2=scene(340))
    case("--mask does not allow past the larger base", 1, scene(300), scene(341), base2=scene(340), expect_in="GREW +1 B")
    case("without --mask the same flush fails", 1, scene(300), scene(340))
    case("a missing scene fails", 1, scene(300) + mark("b"), scene(300), expect_in="missing scene in NEW: b")
    case("an extra scene fails", 1, scene(300), scene(300) + mark("b"), expect_in="extra scene in NEW: b")
    case("a measured flush with no done line fails", 1, scene(300),
         [{"ev": "submit", "id": 999, "label": "WINDOW", "in": 1}] + mark("13-files-menu"), expect_in="no done line")
    case("a scene marked twice fails", 1, scene(300), scene(300) + mark("13-files-menu"), expect_in="marked twice")
    case("a journal with no scene mark fails", 1, scene(300), sub(), expect_in="no scene mark")

    total = ok + len(bad)
    for msg in bad:
        print(f"SELFTEST FAIL {msg}")
    print(f"flushdiff selftest: {ok} of {total} cases pass")
    return 0 if not bad else 1


def main(argv):
    if argv[1:] == ["--selftest"]:
        return selftest()
    args, base2 = [], None
    it = iter(argv[1:])
    for a in it:
        if a == "--mask":
            base2 = next(it, None)
            if base2 is None:
                print("--mask needs a directory or a journal", file=sys.stderr)
                return 2
        elif a.startswith("-"):
            print(f"unknown option {a}\n{__doc__}", file=sys.stderr)
            return 2
        else:
            args.append(a)
    if len(args) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    for p in args + ([base2] if base2 else []):
        if not os.path.exists(p):
            print(f"no such directory or journal: {p}", file=sys.stderr)
            return 2
    return run(args[0], args[1], base2)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
