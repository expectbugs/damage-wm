#!/usr/bin/env python3
"""Damage geometry and ink rules the DESIGN gate runs — DESIGN.md §2.1, §4.2, §9.2b.

`tools/lint.py` runs these over the geometry DESIGN.md declares (its cell table) and over
the rendered surfaces in `design/shots`. The firmware answers none of them: an unaligned
box is rejected in silence, an out-of-panel box leaves the previous frame up.

The runtime rules — stereo pairs, the rect budget, the mode-8 cap, the frame walls, fid
order — are core's `geom` package (`Geometry.kt`, `FidTracker.kt`): the compositor checks them on every emit and
`GeometryTest` pins them. This file's copy of them was retired 2026-09-16 (`HANDOFF.md`
§65): nothing in the repo run called it and it had drifted from the Kotlin (no fid-wrap
branch). One implementation, the one that runs.
"""
from __future__ import annotations
from dataclasses import dataclass

# --- hardware constants, each traceable to a source ------------------------------------
PANEL_W, PANEL_H = 640, 480          # zlib_glue.c PANEL_W/PANEL_H
X_STEP, Y_STEP = 4, 2                # mode-3 box: [left/4][top/2][width/4][height/2]


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


# --- BUD005: ink coverage measured from a render ---------------------------------------
def check_ink(lit: int, total: int, budget: float, *, surface: str) -> list[str]:
    """BUD005 — ink coverage is opacity, distraction and cost at once (DESIGN.md §4.2)."""
    frac = lit / total if total else 0.0
    if frac > budget:
        return [f"BUD005 surface {surface!r} lights {frac:.1%} of its pixels, over its "
                f"{budget:.0%} ink budget — on an additive panel that is opacity and "
                f"transmit cost as well as brightness"]
    return []
