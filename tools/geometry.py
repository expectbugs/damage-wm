#!/usr/bin/env python3
"""Damage geometry and budget rules — DESIGN.md §2.1, §4.2, §8.2, §9.2b.

Most of the failures this file guards against are RUNTIME properties: a rect computed at
frame time is not visible to a source linter. So the rules live here as a library the
compositor calls on every emit, and `tools/lint.py` runs the same functions statically
over the spec's declared geometry and over rendered surfaces.

The firmware answers none of these. An unaligned box is rejected in silence, an over-budget
fid is skipped in silence, a stale delta composites in silence. Every check below exists
because the hardware will not tell you.
"""
from __future__ import annotations
from dataclasses import dataclass, field

# --- hardware constants, each traceable to a source ------------------------------------
PANEL_W, PANEL_H = 640, 480          # zlib_glue.c PANEL_W/PANEL_H
X_STEP, Y_STEP = 4, 2                # mode-3 box: [left/4][top/2][width/4][height/2]
FID_MIN, FID_MAX = 1, 0xFFFE         # 0xFFFF is the CFW's empty-ring sentinel
CFW_FID_RING = 16                    # zlib_glue.c recent_fids[]
MAX_LAYOUT_FRAME = 1000              # e0-20 f1=0/f1=7 wall (overview.md §2)
MAX_IMAGE_FRAGMENT = 3800            # 4096 cap with envelope headroom
MODE8_MAX = 118 + ((((PANEL_W + 1) >> 1) + 3) & ~3) * PANEL_H     # zlib_glue.c bmp_max


class LintError(Exception):
    """Raised loudly. Never caught and logged — that is the failure mode we are avoiding."""


@dataclass(frozen=True)
class Rect:
    x: int
    y: int
    w: int
    h: int

    def __str__(self) -> str:
        return f"({self.x},{self.y} {self.w}x{self.h})"

    @property
    def right(self) -> int:
        return self.x + self.w

    @property
    def bottom(self) -> int:
        return self.y + self.h

    def overlaps(self, other: "Rect") -> bool:
        return not (self.right <= other.x or other.right <= self.x
                    or self.bottom <= other.y or other.bottom <= self.y)


# --- GEO: a box the firmware would silently reject --------------------------------------
def check_rect(r: Rect, *, what: str = "rect") -> list[str]:
    """GEO001 alignment · GEO002 bounds · GEO003 degenerate."""
    out = []
    if r.x % X_STEP or r.w % X_STEP:
        out.append(f"GEO001 {what} {r}: x and width must be multiples of {X_STEP} "
                   f"(x%4={r.x % X_STEP}, w%4={r.w % X_STEP}) — mode-3 encodes left/4 and width/4")
    if r.y % Y_STEP or r.h % Y_STEP:
        out.append(f"GEO001 {what} {r}: y and height must be multiples of {Y_STEP} "
                   f"(y%2={r.y % Y_STEP}, h%2={r.h % Y_STEP}) — mode-3 encodes top/2 and height/2")
    if r.w <= 0 or r.h <= 0:
        out.append(f"GEO003 {what} {r}: zero or negative extent is rejected by the firmware")
    if r.x < 0 or r.y < 0 or r.right > PANEL_W or r.bottom > PANEL_H:
        out.append(f"GEO002 {what} {r}: outside the {PANEL_W}x{PANEL_H} panel "
                   f"(right={r.right}, bottom={r.bottom}) — box is rejected in SILENCE, "
                   f"leaving the previous frame up")
    return out


def check_stereo_pair(left: Rect, right: Rect) -> list[str]:
    """GEO004 — the firmware rejects a lenses-differ pair whose boxes differ in size.

    zlib_glue.c: `if (src[3] != src[7] || src[4] != src[8]) return -1;`
    """
    out = check_rect(left, what="stereo L") + check_rect(right, what="stereo R")
    if (left.w, left.h) != (right.w, right.h):
        out.append(f"GEO004 stereo pair {left} / {right}: boxes must be the SAME SIZE; "
                   f"only the position may differ")
    if left.y != right.y:
        out.append(f"GEO005 stereo pair {left} / {right}: vertical disparity is forbidden "
                   f"(DESIGN.md §3.4) — horizontal offsets only")
    if abs(left.x - right.x) % X_STEP:
        out.append(f"GEO006 stereo pair {left} / {right}: disparity {abs(left.x - right.x)} px "
                   f"is not a multiple of {X_STEP}; the ladder is 0/4/8/12/16")
    return out


def check_cells(cells: dict[str, Rect], *, span: Rect | None = None) -> list[str]:
    """GEO007 chrome cells must tile their bar without overlap or gaps."""
    out: list[str] = []
    for name, r in cells.items():
        out += [f"{e}  [cell {name}]" for e in check_rect(r, what=name)]
    names = list(cells)
    for i, a in enumerate(names):
        for b in names[i + 1:]:
            if cells[a].overlaps(cells[b]):
                out.append(f"GEO007 cells {a} {cells[a]} and {b} {cells[b]} overlap")
    if span is not None and cells:
        covered = sum(r.w for r in cells.values())
        if covered != span.w:
            out.append(f"GEO008 cells cover {covered} px of a {span.w} px bar "
                       f"({'gap' if covered < span.w else 'overflow'} of {abs(span.w - covered)})")
    return out


# --- BUD: budgets that fail silently on the wire ----------------------------------------
def rect_budget(window: int) -> int:
    """DESIGN.md §8.2 — rects x window <= CFW_FID_RING. Only mode-3 consumes a fid."""
    if window < 1:
        raise LintError(f"pipeline window must be >= 1, got {window}")
    return max(1, CFW_FID_RING // window)


def check_batch(deltas: list[Rect], *, window: int = 3, copies: int = 0,
                payload: int | None = None) -> list[str]:
    """BUD001 rect budget · BUD002 mode-8 size cap."""
    out: list[str] = []
    budget = rect_budget(window)
    if len(deltas) > budget:
        out.append(f"BUD001 {len(deltas)} mode-3 rects with a {window}-deep pipeline exceeds "
                   f"the budget of {budget} (rects x window <= {CFW_FID_RING}); a retransmit "
                   f"would age out of the duplicate ring and be RE-APPLIED, not skipped")
    for i, r in enumerate(deltas):
        out += [f"{e}  [batch rect {i}]" for e in check_rect(r, what=f"delta{i}")]
    if payload is not None and payload > MODE8_MAX:
        out.append(f"BUD002 mode-8 batch {payload} B exceeds the firmware cap of {MODE8_MAX} B")
    if copies < 0:
        raise LintError("negative mode-9 copy count")
    return out


def check_frame_size(nbytes: int, *, kind: str) -> list[str]:
    """BUD003 layout/CREATE wall · BUD004 image fragment size."""
    if kind == "layout" and nbytes > MAX_LAYOUT_FRAME:
        return [f"BUD003 layout/CREATE frame {nbytes} B exceeds ~{MAX_LAYOUT_FRAME} B; the "
                f"firmware ignores it with NO ack and NO error"]
    if kind == "image" and nbytes > MAX_IMAGE_FRAGMENT:
        return [f"BUD004 image fragment {nbytes} B exceeds {MAX_IMAGE_FRAGMENT} B"]
    return []


def check_ink(lit: int, total: int, budget: float, *, surface: str) -> list[str]:
    """BUD005 — ink coverage is opacity, distraction and cost at once (DESIGN.md §4.2)."""
    frac = lit / total if total else 0.0
    if frac > budget:
        return [f"BUD005 surface {surface!r} lights {frac:.1%} of its pixels, over its "
                f"{budget:.0%} ink budget — on an additive panel that is opacity and "
                f"transmit cost as well as brightness"]
    return []


# --- FID: ordering state the firmware tracks but never reports ---------------------------
@dataclass
class FidTracker:
    """FID001 reuse · FID002 gap · FID003 range · FID004 delta before keyframe.

    Mirrors zlib_glue.c cfw_diag(): only an exact hit in the last-16 ring is SKIPPED; a
    stale fid that has aged out is flagged and then APPLIED, clobbering newer pixels. So
    the rule is never to put a fid on the wire twice, not to hope the ring saves you.
    """
    last: int | None = None
    seeded: bool = False
    ring: list[int] = field(default_factory=list)
    issued: set[int] = field(default_factory=set)

    def keyframe(self) -> list[str]:
        self.seeded = True
        self.last = None                      # mode 6 rebaselines the next delta
        return []

    def delta(self, fid: int) -> list[str]:
        out: list[str] = []
        if not self.seeded:
            out.append("FID004 mode-3 delta with no prior mode-6 keyframe; the shadow is unseeded")
        if not (FID_MIN <= fid <= FID_MAX):
            out.append(f"FID003 fid {fid} outside [{FID_MIN}, {FID_MAX}] "
                       f"(0xFFFF is the empty-ring sentinel)")
        if fid in self.issued:
            out.append(f"FID001 fid {fid} reused; the same id must never reach the wire twice")
        if self.last is not None:
            d = (fid - self.last) & 0xFFFF
            if d == 0 or d >= 0x8000:
                out.append(f"FID002 fid went backward ({self.last} -> {fid}); sets f_reorder")
            elif d > 1:
                out.append(f"FID002 fid gap ({self.last} -> {fid}, +{d}); sets f_skip. "
                           f"Allocate at EMIT time, never at plan time")
        self.issued.add(fid)
        self.ring = (self.ring + [fid])[-CFW_FID_RING:]
        self.last = fid
        return out
