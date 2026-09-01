#!/usr/bin/env python3
"""Render Damage shell mockups at true 640x480 / 4bpp, and price each one.

Every screen here obeys DESIGN.md: the quantization grid (x,w x4 - y,h x2), the
restrained ~5-level ramp, filled highlights, no panels or backgrounds, and glyph
origins snapped to the 4px x / 2px y grid. Output is the actual framebuffer we would
send, so the ink % and byte cost printed alongside are real, not modelled.
"""
import sys, zlib
from PIL import Image, ImageDraw, ImageFont
sys.path.insert(0, "/home/user/damagewm/research")
from fbfeas import rle_nibble                      # the firmware's real RLE

W, H = 640, 480
TOP_H, DIV = 32, 2
CX, CY, CW, CH = 16, 34, 608, 416
DIV2_Y, ST_Y, ST_H = 450, 452, 28
# Bars inset to the content extent since 2026-08-31 (REFINEMENT.md §1): chrome
# sits behind the content plane and needs the same 16 px stereo-shift budget.
BAR_X, BAR_R = 16, 624                              # bar extent (x16, w608)
TITLE_W, BATT_X, CLK_X = 368, 368, 544              # ribbon retired 2026-08-18
LENS_Y, LENS_H, ROW_H, PAD = 210, 64, 32, 16
RAIL_X, RAIL_W = 612, 12

L = lambda n: n * 17                                # 4bpp level -> 8-bit
BG, DIM, RULE, BODY, HEAD, HOT = L(0), L(3), L(3), L(8), L(12), L(15)

FAMILIES = {
    # Adam's wishlist, mapped to what is actually installed (URW Base-35 clones).
    "Clear Sans":       ("/usr/share/fonts/clearsans/ClearSans-Regular.ttf",           "/usr/share/fonts/clearsans/ClearSans-Bold.ttf"),
    "DejaVu Sans":      ("/usr/share/fonts/dejavu/DejaVuSans.ttf",              "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf"),
    "Helvetica":        ("/usr/share/fonts/urw-fonts/NimbusSans-Regular.otf",      "/usr/share/fonts/urw-fonts/NimbusSans-Bold.otf"),
    "Helvetica Narrow": ("/usr/share/fonts/urw-fonts/NimbusSansNarrow-Regular.otf","/usr/share/fonts/urw-fonts/NimbusSansNarrow-Bold.otf"),
    "Futura-ish":       ("/usr/share/fonts/urw-fonts/URWGothic-Book.otf",          "/usr/share/fonts/urw-fonts/URWGothic-Demi.otf"),
    "Times":            ("/usr/share/fonts/urw-fonts/NimbusRoman-Regular.otf",     "/usr/share/fonts/urw-fonts/NimbusRoman-Bold.otf"),
    "Palatino":         ("/usr/share/fonts/urw-fonts/P052-Roman.otf",              "/usr/share/fonts/urw-fonts/P052-Bold.otf"),
    "Bookman":          ("/usr/share/fonts/urw-fonts/URWBookman-Light.otf",        "/usr/share/fonts/urw-fonts/URWBookman-Demi.otf"),
    "Century Schbk":    ("/usr/share/fonts/urw-fonts/C059-Roman.otf",              "/usr/share/fonts/urw-fonts/C059-Bold.otf"),
    "Chancery":         ("/usr/share/fonts/urw-fonts/Z003-MediumItalic.otf",       "/usr/share/fonts/urw-fonts/Z003-MediumItalic.otf"),
    "Courier":          ("/usr/share/fonts/urw-fonts/NimbusMonoPS-Regular.otf",    "/usr/share/fonts/urw-fonts/NimbusMonoPS-Bold.otf"),
    "Liberation Sans":  ("/usr/share/fonts/liberation-fonts/LiberationSans-Regular.ttf", "/usr/share/fonts/liberation-fonts/LiberationSans-Bold.ttf"),
    "DejaVu Mono":      ("/usr/share/fonts/dejavu/DejaVuSansMono.ttf",          "/usr/share/fonts/dejavu/DejaVuSansMono-Bold.ttf"),
}
FAM = ["/usr/share/fonts/clearsans/ClearSans-Regular.ttf", "/usr/share/fonts/clearsans/ClearSans-Bold.ttf"]   # SYSTEM FACE (§4.1)
SYS_SCALE = 1.078                                     # normalised to DejaVu x-height
def font(sz, b=False, c=False):
    return ImageFont.truetype(FAM[1] if b else FAM[0], sz)
f_chrome, f_chromeb = font(15), font(15, b=True)
f_rib, f_ribb = font(13), font(13, b=True)
f_small, f_row, f_rowb = font(12, b=True), font(17), font(17, b=True)
f_body, f_big, f_tiny = font(16), font(20, b=True), font(11, b=True)
f_tel = font(12)
f_batt = font(10, b=True)
f_battl = font(14, b=True)

def set_content_family(name, scale=1.0):
    """Content fonts ONLY. Chrome stays on the system face — §4.1: the bars are the
    constant frame, and varying them would defeat the per-window identity cue by making
    everything variable."""
    global FAM, f_small, f_row, f_rowb, f_body, f_big
    FAM = list(FAMILIES[name])
    z = lambda n: max(7, int(round(n * scale)))
    f_small, f_row, f_rowb = font(z(12), b=True), font(z(17)), font(z(17), b=True)
    f_body, f_big = font(z(16)), font(z(20), b=True)
    FAM = list(FAMILIES["Clear Sans"])

def set_family(name, scale=1.0):
    """Rebuild every font global for a different face / size, so a screen can be
    compared in situ rather than from a specimen strip."""
    global FAM, f_chrome, f_chromeb, f_rib, f_ribb, f_small, f_row, f_rowb
    global f_body, f_big, f_tiny, f_tel
    FAM = list(FAMILIES[name])
    z = lambda n: max(7, int(round(n * scale)))
    f_chrome, f_chromeb = font(z(15)), font(z(15), b=True)
    f_rib, f_ribb = font(z(13)), font(z(13), b=True)
    f_small, f_row, f_rowb = font(z(12), b=True), font(z(17)), font(z(17), b=True)
    f_body, f_big, f_tiny = font(z(16)), font(z(20), b=True), font(z(11), b=True)
    f_tel = font(z(12))
    global f_batt
    f_batt = font(z(10), b=True)
    global f_battl
    f_battl = font(z(14), b=True)

def tri(d, x, y, h, lv, left=False):
    """UI symbols are DRAWN, never typed. Verified 2026-08-18: U+25B8 and U+25B6 are
    absent from 13 of 16 candidate faces and U+2699 from 15 of 16 — typing them would
    ship tofu boxes, and would silently couple typeface choice to symbol coverage."""
    for i in range(h):
        w = (h - abs(h - 1 - 2 * i)) // 2
        d.rectangle([x, y + i, x + max(0, w), y + i], fill=lv) if not left else \
            d.rectangle([x - max(0, w), y + i, x, y + i], fill=lv)

def snap(x): return (int(x) // 4) * 4               # glyph origins on the 4px grid
def text(d, xy, s, fill, fnt, anchor=None):
    x, y = xy
    d.text((snap(x), (int(y) // 2) * 2), s, fill=fill, font=fnt, anchor=anchor)
def rtext(d, xy, s, fill, fnt):                     # right-aligned
    x, y = xy
    d.text((x, (int(y) // 2) * 2), s, fill=fill, font=fnt, anchor="ra")



def battery_icon(d, x, y, kind, lvl, low):
    """Device-shaped gauge: outline always drawn, FILL drains from the top with charge.
    Urgency rides on brightness, not on shape, so a flat device is still identifiable as
    WHICH device — which a bare numeral would lose (DESIGN.md 4.1)."""
    lit = HOT if low else BODY
    def gauge(a_, b_, c_, e_, ellipse=False):
        h_ = e_ - b_
        cut = b_ + int(h_ * (1 - lvl))
        if ellipse:
            d.ellipse([a_, b_, c_, e_], outline=lit, width=3)
            if cut < e_ - 3:
                d.ellipse([a_ + 3, max(cut, b_ + 3), c_ - 3, e_ - 3], fill=lit)
        else:
            d.rectangle([a_, b_, c_, e_], outline=lit, width=3)
            if cut < e_ - 3:
                d.rectangle([a_ + 3, max(cut, b_ + 3), c_ - 3, e_ - 3], fill=lit)
    if kind == "glasses":                                   # two lenses + a bridge
        w, h = 26, 14
        gauge(x, y, x + 10, y + h)
        gauge(x + 16, y, x + 26, y + h)
        d.rectangle([x + 10, y + 5, x + 16, y + 8], fill=lit)
    elif kind == "phone":
        w, h = 14, 24
        gauge(x, y, x + w, y + h)
        d.rectangle([x + 4, y + 2, x + 10, y + 4], fill=BG)  # earpiece slot
    else:                                                    # ring
        w, h = 18, 18
        gauge(x, y, x + w, y + h, ellipse=True)
        d.ellipse([x + 6, y + 6, x + w - 6, y + h - 6], fill=BG)
    return w

# ---------------------------------------------------------------- icons
def icon(d, x, y, w, h, kind, lv):
    """Thick strokes, closed forms, no hairlines (DESIGN.md 2.4 rule 9)."""
    t = max(3, h // 10)
    if kind == "terminal":
        d.rectangle([x, y, x + w - 1, y + h - 1], fill=lv)
        d.rectangle([x + t, y + t * 2, x + w - 1 - t, y + h - 1 - t], fill=BG)
        cw = max(3, w // 8)
        for i in range(cw * 2):                       # a chunky ">" caret
            d.rectangle([x + t * 2 + i, y + h // 2 - cw + i, x + t * 2 + i + t, y + h // 2 - cw + i + t], fill=lv)
        for i in range(cw * 2):
            d.rectangle([x + t * 2 + i, y + h // 2 + cw - i, x + t * 2 + i + t, y + h // 2 + cw - i + t], fill=lv)
        d.rectangle([x + w // 2, y + h - t * 3, x + w - t * 2, y + h - t * 2], fill=lv)
    elif kind == "calendar":
        d.rectangle([x, y, x + w - 1, y + h - 1], fill=lv)
        d.rectangle([x + t, y + h // 3, x + w - 1 - t, y + h - 1 - t], fill=BG)
        for c in range(3):
            for r in range(2):
                dx = x + t * 2 + c * (w - t * 4) // 3
                dy = y + h // 3 + t + r * (h // 4)
                d.rectangle([dx, dy, dx + t, dy + t], fill=lv)
    elif kind == "music":
        d.rectangle([x + w - t * 2, y, x + w, y + h - t * 3], fill=lv)
        d.rectangle([x + w // 3, y + t * 2, x + w, y + t * 3], fill=lv)
        d.rectangle([x + w // 3, y + t * 2, x + w // 3 + t, y + h - t * 2], fill=lv)
        d.ellipse([x, y + h - t * 4, x + t * 4, y + h], fill=lv)
        d.ellipse([x + w // 3, y + h - t * 5, x + w // 3 + t * 4, y + h - t], fill=lv)
    elif kind == "timer":
        d.ellipse([x, y + t, x + w, y + h], outline=lv, width=t)
        d.rectangle([x + w // 2 - t, y, x + w // 2 + t, y + t * 2], fill=lv)
        d.line([x + w // 2, y + h // 2 + t // 2, x + w // 2, y + t * 3], fill=lv, width=t)
    elif kind == "sms":
        d.rectangle([x, y, x + w - 1, y + h - t * 3], fill=lv)
        d.rectangle([x + t, y + t, x + w - 1 - t, y + h - t * 4], fill=BG)
        d.polygon([(x + t * 2, y + h - t * 3), (x + t * 5, y + h - t * 3),
                   (x + t * 2, y + h)], fill=lv)
    elif kind == "reader":
        d.rectangle([x, y, x + w - 1, y + h - 1], fill=lv)
        d.rectangle([x + t, y + t, x + w // 2 - t // 2, y + h - 1 - t], fill=BG)
        d.rectangle([x + w // 2 + t // 2, y + t, x + w - 1 - t, y + h - 1 - t], fill=BG)
    elif kind == "files":
        d.rectangle([x, y + t * 2, x + w - 1, y + h - 1], fill=lv)
        d.rectangle([x, y, x + w // 2, y + t * 2], fill=lv)
        d.rectangle([x + t, y + t * 4, x + w - 1 - t, y + h - 1 - t], fill=BG)
    elif kind == "notices":
        d.ellipse([x + t, y, x + w - 1 - t, y + h - t * 3], fill=lv)
        d.rectangle([x + t, y + h // 2, x + w - 1 - t, y + h - t * 3], fill=lv)
        d.rectangle([x, y + h - t * 3, x + w - 1, y + h - t * 2], fill=lv)
        d.rectangle([x + w // 2 - t, y + h - t * 2, x + w // 2 + t, y + h], fill=lv)
    elif kind == "scout":
        d.ellipse([x, y, x + w - t * 3, y + h - t * 3], outline=lv, width=t)
        d.line([x + w - t * 4, y + h - t * 4, x + w - 1, y + h - 1], fill=lv, width=t + 1)
    elif kind == "settings":
        d.ellipse([x, y, x + w - 1, y + h - 1], fill=lv)
        d.ellipse([x + t * 2, y + t * 2, x + w - 1 - t * 2, y + h - 1 - t * 2], fill=BG)
        for a in range(0, 360, 60):
            import math
            r = math.radians(a)
            d.rectangle([x + w // 2 + (w // 2) * math.cos(r) - t,
                         y + h // 2 + (h // 2) * math.sin(r) - t,
                         x + w // 2 + (w // 2) * math.cos(r) + t,
                         y + h // 2 + (h // 2) * math.sin(r) + t], fill=lv)
    elif kind == "mail":
        d.rectangle([x, y, x + w - 1, y + h - 1], fill=lv)
        d.rectangle([x + t, y + t, x + w - 1 - t, y + h - 1 - t], fill=BG)
        for i in range(w // 2 - t):                   # the flap
            d.rectangle([x + t + i, y + t + i, x + t + i + t, y + t + i + t], fill=lv)
            d.rectangle([x + w - 1 - t - i - t, y + t + i, x + w - 1 - t - i, y + t + i + t], fill=lv)



def compass_tape(d, x, y, w, heading):
    """A heading TAPE, not a two-letter label: wide-and-short is the cheap shape on this
    display (rows carry the RLE runs), and a tape is unambiguous about which way it
    scrolls. The current sector sits under a fixed centre mark."""
    SECT = ("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    i = SECT.index(heading)
    for k in (-1, 0, 1):
        sc = SECT[(i + k) % 8]
        lv = BODY if k == 0 else L(3)
        fnt = f_chromeb if k == 0 else f_tel
        cx = x + w // 2 + k * 34
        text(d, (cx - d.textlength(sc, fnt) / 2, y + (6 if k == 0 else 9)), sc, lv, fnt)
        d.rectangle([cx - 1, y + 22, cx + 1, y + 25], fill=L(2))
    d.rectangle([x + w // 2 - 3, y + 2, x + w // 2 + 3, y + 4], fill=HOT)   # centre mark

def blocks(d, x, y, w, h, frac, n=8, lv=BODY):
    """Coarse discrete progress. Solid blocks are long RLE runs; a smooth bar is not."""
    bw = (w - (n - 1) * 2) // n
    for k in range(n):
        bx = x + k * (bw + 2)
        d.rectangle([bx, y, bx + bw, y + h], fill=lv if (k + 1) / n <= frac else L(1))

def battery_bar(d, x, y, w, h, pct, lv):
    """A plain fill bar, deliberately NOT segmented. Measured 2026-08-18: at this width
    20 segments read as a solid bar anyway, and pure fill LENGTH is the most legible of
    the options tested — 0.9 px per percent, so 45% and 50% differ by ~2.8 px. It is also
    the cheapest: one run per row instead of twenty."""
    d.rectangle([x, y, x + w, y + h], outline=lv, width=2)
    d.rectangle([x + w + 1, y + h // 3, x + w + 4, y + h - h // 3], fill=lv)   # nub
    fill = int((w - 6) * pct / 100)
    if fill > 0: d.rectangle([x + 3, y + 3, x + 3 + fill, y + h - 3], fill=lv)

# --- battery readout -------------------------------------------------------
# Brightness tracks charge, so a flat battery is DIM at rest and costs less ink than a
# healthy one. Attention is carried by CHANGE, not by steady brightness: at <=20% the
# numeral pulses bright-medium-dim-medium-bright-medium-dim on a slow cycle. Quantised
# steps, never a smooth fade (DESIGN.md 4.3).
FLASH_SEQ = (15, 8, 3, 8, 15, 8, 3)
def batt_level(pct, phase=None):
    if phase is not None:
        return L(FLASH_SEQ[phase % len(FLASH_SEQ)])
    return L(max(2, min(10, 2 + int(8 * pct / 100))))

def new():
    im = Image.new("L", (W, H), BG)
    return im, ImageDraw.Draw(im)

# ---------------------------------------------------------------- shared chrome
def chrome(d, title, win, win_ic, *, pos_frac=0.4, depth=2, dirty=(), op="idle",
           status="ok", thru="8.4K/s · 176ms", comp="NE", link=-58,
           batt=(("g", 87), ("p", 62)), flash=0, nwin=13, at=5,   # no R: ring battery has no source (CLAIMS.md)
           dirty_at=(1, 9)):
    # --- top bar: [icon] WINDOW · document | batteries | clock -------------------
    icon(d, BAR_X + 8, 6, 20, 20, win_ic, HEAD)
    text(d, (BAR_X + 36, 7), win, HEAD, f_chromeb)
    wx = BAR_X + 36 + d.textlength(win, f_chromeb) + 8
    if title: text(d, (wx, 7), "· " + title, DIM, f_chrome)
    if wx + d.textlength("· " + title, f_chrome) > TITLE_W - 12:
        tri(d, TITLE_W - 12, 12, 9, DIM)                               # drawn continuation
    for i, (tag, pct) in enumerate(batt):
        bx = BATT_X + 4 + i * 58
        lv = batt_level(pct, flash if pct <= 20 else None)
        text(d, (bx, 6), tag.upper(), lv, f_battl)          # CAPITAL, larger
        battery_bar(d, bx + 14, 10, 30, 14, pct, lv)
    text(d, (CLK_X + 4, 6), "12:59", HEAD, f_chromeb)
    text(d, (CLK_X + 52, 9), "PM", DIM, f_tiny)
    # --- top divider = window position + attention marks (the ribbon's whole job) ---
    d.rectangle([BAR_X, TOP_H, BAR_R - 1, TOP_H + DIV - 1], fill=L(1))
    slot = (BAR_R - BAR_X) / nwin
    for k in dirty_at:
        d.rectangle([BAR_X + int(k * slot) + 4, TOP_H, BAR_X + int(k * slot) + 16, TOP_H + DIV - 1], fill=L(6))
    d.rectangle([BAR_X + int(at * slot), TOP_H, BAR_X + int((at + 1) * slot) - 2, TOP_H + DIV - 1], fill=HEAD)
    # --- bottom divider = back-stack depth ---
    d.rectangle([BAR_X, DIV2_Y, BAR_R - 1, DIV2_Y + DIV - 1], fill=L(1))
    for i in range(depth):
        d.rectangle([BAR_X + 16 + i * 26, DIV2_Y, BAR_X + 16 + i * 26 + 18, DIV2_Y + DIV - 1], fill=HEAD)
    # --- status bar (op 128 · stat 132 · thru 128 · tape 100 · link 120 on the 608 bar) ---
    text(d, (BAR_X + 8, ST_Y + 7), op, BODY, f_chrome)          # op   x16  w128
    text(d, (152, ST_Y + 7), status, DIM, f_chrome)             # stat x144 w132
    text(d, (284, ST_Y + 9), thru, DIM, f_tel)                  # thru x276 w128
    compass_tape(d, 404, ST_Y, 100, comp)                       # tape x404 w100
    poor = link <= -75
    for i in range(4):                                   # signal bars, right edge of the link cell
        h_ = 4 + i * 4
        on = (link + 95) / 40 * 4 > i
        bx = BAR_R - 44 + i * 8
        d.rectangle([bx, ST_Y + 20 - h_, bx + 5, ST_Y + 20], fill=(HOT if poor else BODY) if on else L(1))
    if poor: rtext(d, (BAR_R - 48, ST_Y + 9), f"{link}", HOT, f_tel)

def rail(d, frac, span):
    d.rectangle([RAIL_X + 4, CY, RAIL_X + 7, CY + CH - 1], fill=L(1))
    t = CY + int(frac * (CH - span))
    d.rectangle([RAIL_X + 4, t, RAIL_X + 7, t + span], fill=BODY)

def lens(d, name, l1, l2, *, level=HEAD, ic=None, prog=None):
    d.rectangle([CX, LENS_Y, CX + CW - 1, LENS_Y + 1], fill=RULE)        # bracketing rules
    d.rectangle([CX, LENS_Y + LENS_H - 2, CX + CW - 1, LENS_Y + LENS_H - 1], fill=RULE)
    # BAND-HEIGHT icon (Adam, 2026-09-01 — DESIGN §4.5b revised): the focused
    # row's icon spans the 64 px lens at the switcher-class 56 px
    if ic: icon(d, 24, LENS_Y + 4, 56, 56, ic, level)
    text(d, (88, LENS_Y + 8), name, level, f_rowb)
    rtext(d, (592, LENS_Y + 8), l1, BODY, f_row)
    text(d, (32 if not ic else 88, LENS_Y + 34), l2, BODY, f_row)
    if l2 and d.textlength(l2, f_row) > 500: tri(d, 584, LENS_Y + 40, 11, DIM)
    if prog is not None: blocks(d, 400, LENS_Y + 40, 196, 8, prog)

def row(d, y, name, summary, more=False, lv=BODY, nv=DIM, ic=None, prog=None):
    if ic: icon(d, 28, y + 6, 20, 20, ic, nv)
    text(d, (56, y + 7), name, nv, f_small)
    text(d, (176, y + 5), summary, lv, f_row)
    if prog is not None: blocks(d, 452, y + 12, 144, 6, prog, lv=L(4))
    if more: tri(d, 588, y + 11, 11, DIM)

# ---------------------------------------------------------------- the screens
ROWS_ABOVE = [("CALENDAR", "Standup 09:30 · in 22m", False, "calendar"),
              ("MUSIC",    "Bowie - Blackstar", False, "music"),
              ("TIMERS",   "2 pending · next 14m", False, "timer"),
              ("MAIL",     "3 unread · Jane Doe + 2", False, "mail"),
              ("SMS",      'Mom · "on my way"', True, "sms")]
ROWS_BELOW = [("READER",  "Dune · p.412 of 604", False, "reader", 0.68),
              ("FILES",   "~/damagewm", False, "files"),
              ("NOTICES", "4 unread", False, "notices"),
              ("SCOUT",   "idle", False, "scout"),
              ("SETTINGS", "brightness · size · depth · presence", False, "settings")]

def main_screen(resting=False):
    im, d = new()
    chrome(d, "13 windows · 4 unread", "MAIN", "settings", depth=1, at=5,
           op="rendering" if not resting else "idle")
    if resting:
        lens(d, "TERMINAL", "build #482 · 4m12", "12 tests passed, 0 failed", ic="terminal")
        for i, (n, _, _, ic) in enumerate(ROWS_ABOVE):
            text(d, (56, CY + PAD + i * ROW_H + 7), n, L(2), f_small)   # no icons at rest
        for i, r in enumerate(ROWS_BELOW):
            text(d, (56, LENS_Y + LENS_H + i * ROW_H + 7), r[0], L(2), f_small)
    else:
        for i, (n, s_, m, ic) in enumerate(ROWS_ABOVE):
            row(d, CY + PAD + i * ROW_H, n, s_, m, ic=ic)
        lens(d, "TERMINAL", "build #482 · 4m12", "12 tests passed, 0 failed", ic="terminal", prog=0.62)
        for i, r in enumerate(ROWS_BELOW):
            row(d, LENS_Y + LENS_H + i * ROW_H, r[0], r[1], r[2], ic=r[3],
                prog=r[4] if len(r) > 4 else None)
    return im

def switcher_screen():
    im, d = new()
    chrome(d, "p.412 of 604", "DUNE", "reader", depth=1, at=6, op="preview")
    for i in range(9):                                     # the previewed window behind
        text(d, (32, CY + PAD + i * 34 + 4),
             ["CHAPTER XI", "", "The spice must flow, and the sleeper", "must awaken. Paul watched the",
              "worm crest the dune line, its rings", "catching the second moon.", "",
              "“A process cannot be understood by”", "“stopping it.”"][i],
             L(4) if i else L(6), f_body)

    px, py, pw, ph = 200, 154, 240, 176
    d.rectangle([px, py, px + pw - 1, py + ph - 1], fill=BG)          # the wheel clears its hole

    # neighbours: cos(60deg)=0.5 -> exactly half height, dim, clipped by the panel edge
    icon(d, px + 44, py + 6, 40, 20, "calendar", L(4))
    text(d, (px + 96, py + 8), "Calendar", L(4), f_body)
    icon(d, px + 44, py + 150, 40, 20, "mail", L(4))
    text(d, (px + 96, py + 150), "Mail", L(4), f_body)
    d.rectangle([px, py + 42, px + pw - 1, py + 43], fill=RULE)       # band rules
    d.rectangle([px, py + 132, px + pw - 1, py + 133], fill=RULE)

    # centre: full size, full brightness, plane 0
    icon(d, px + 92, py + 46, 56, 56, "terminal", HEAD)
    tw = d.textlength("Terminal", f_big)
    lx = px + (pw - tw) / 2
    text(d, (lx, py + 106), "Terminal", HOT, f_big)
    d.rectangle([int(lx + tw) + 8, py + 112, int(lx + tw) + 12, py + 122], fill=HOT)  # dirty tick
    return im

def notification_screen(emergency=False):
    im, d = new()
    chrome(d, "p.412 of 604", "DUNE", "reader", depth=2, at=6, op="reading")
    for i in range(9):
        text(d, (32, CY + PAD + i * 34 + 4),
             ["CHAPTER XI", "", "The spice must flow, and the sleeper", "must awaken. Paul watched the",
              "worm crest the dune line, its rings", "catching the second moon.", "",
              "“A process cannot be understood by”", "“stopping it.”"][i],
             L(4) if i else L(6), f_body)
    if emergency:
        bx, by, bw, bh = 16, 202, 608, 76
        d.rectangle([bx, by, bx + bw - 1, by + bh - 1], fill=BG)
        for yy in (by, by + 4, by + bh - 6, by + bh - 2):
            d.rectangle([bx, yy, bx + bw - 1, yy + 1], fill=HOT)
        text(d, (32, by + 14), "EMERGENCY ALERT · NWS", HOT, f_small)
        text(d, (32, by + 34), "Tornado Warning — Travis County until 4:15 PM", HOT, f_big)
    else:
        bx, by, bw, bh = 196, 190, 248, 104
        d.rectangle([bx, by, bx + bw - 1, by + bh - 1], fill=BG)
        text(d, (bx + 8, by + 3), "SMS · MOM", HEAD, f_small)
        text(d, (bx + 96, by + 3), "+2", DIM, f_tiny)
        rtext(d, (bx + bw - 8, by + 3), "14:32", DIM, f_tiny)
        d.rectangle([bx, by + 16, bx + bw - 1, by + 17], fill=RULE)
        text(d, (bx + 8, by + 26), "on my way, should be", BODY, f_body)
        text(d, (bx + 8, by + 50), "there in about twenty", BODY, f_body)
        text(d, (bx + 8, by + 74), "minutes", BODY, f_body)
        d.rectangle([bx, by + bh - 2, bx + bw - 1, by + bh - 1], fill=L(1))
        d.rectangle([bx + 40, by + bh - 2, bx + 130, by + bh - 1], fill=HEAD)
    return im

def window_list_screen():
    im, d = new()
    chrome(d, "3 unread", "INBOX", "mail", depth=2, at=3, op="fetching")
    above = [("09:14", "Jane Doe", "Re: damage-wm scope"), ("08:52", "builds@ci", "#482 passed"),
             ("08:03", "Mom", "dinner sunday?"), ("Tue", "James Babcock", "texture cache"),
             ("Tue", "no-reply", "Your order shipped")]
    below = [("Mon", "Kalani H.", "thumb audit results"), ("Mon", "aria", "digest 4 items"),
             ("Sun", "Jane Doe", "photos from saturday"), ("Sat", "list", "[g2] weekly"),
             ("⋯", "ACTIONS", "compose · search · mark all")]
    for i, (t, who, sub) in enumerate(above):
        y = CY + PAD + i * ROW_H
        text(d, (32, y + 8), t, DIM, f_small); text(d, (100, y + 5), who, HEAD, f_row)
        text(d, (256, y + 5), sub, BODY, f_row)
    lens(d, "Jane Doe", "09:14", "Re: damage-wm scope - the ribbon cells need to be equal")
    for i, (t, who, sub) in enumerate(below):
        y = LENS_Y + LENS_H + i * ROW_H
        text(d, (32, y + 8), t, DIM, f_small); text(d, (100, y + 5), who, HEAD, f_row)
        text(d, (256, y + 5), sub, BODY, f_row)
    rail(d, 0.35, 90)
    return im

def window_doc_screen():
    im, d = new()
    chrome(d, "p.412 of 604 · 68%", "DUNE", "reader", depth=3, at=6, op="reading")
    lines = ["CHAPTER XI", "",
             "The spice must flow, and the sleeper must", "awaken. Paul watched the worm crest the",
             "dune line, its rings catching the second", "moon, and understood at last what his",
             "mother had refused to say aloud.", "",
             "“A process cannot be understood by stopping", " it. Understanding must move with the flow",
             " of the process, must join it and flow with", " it.”", "",
             "He let the thought settle. Below, the", "Fremen were already breaking camp."]
    for i, ln in enumerate(lines):
        text(d, (32, CY + PAD + i * 24 + 2), ln, HEAD if i == 0 else BODY,
             f_rowb if i == 0 else f_body)
    rail(d, 0.68, 60)
    return im

SEG7 = [0b1111110, 0b0110000, 0b1101101, 0b1111001, 0b0110011,
        0b1011011, 0b1011111, 0b1110000, 0b1111111, 0b1111011]   # A..G = bit6..bit0

def seven_seg_clock(d, x0, y0, hh, mm):
    """§1.5 third revision (Adam 2026-08-31): drawn LED-style seven-segment
    digits, 12-hour, no leading zero — tapered hex segments, softened edge
    rows, corner gaps. 138x44 from (x0,y0). Keep in lockstep with core
    Icons.sevenSegClock()."""
    w, t = 26, 6
    def hseg(x, y, ln):
        for r in range(t):
            inset = abs(2 * r - (t - 1)) // 2
            d.rectangle([x + inset, y + r, x + ln - inset - 1, y + r],
                        fill=L(6) if r in (0, t - 1) else L(9))
    def vseg(x, y, ln):
        for c in range(t):
            inset = abs(2 * c - (t - 1)) // 2
            d.rectangle([x + c, y + inset, x + c, y + ln - inset - 1],
                        fill=L(6) if c in (0, t - 1) else L(9))
    def digit(x, n):
        m = SEG7[n]
        if m & 0b1000000: hseg(x + 4, y0, w - 8)              # A
        if m & 0b0100000: vseg(x + w - t, y0 + 7, 12)         # B
        if m & 0b0010000: vseg(x + w - t, y0 + 26, 12)        # C
        if m & 0b0001000: hseg(x + 4, y0 + 38, w - 8)         # D
        if m & 0b0000100: vseg(x, y0 + 26, 12)                # E
        if m & 0b0000010: vseg(x, y0 + 7, 12)                 # F
        if m & 0b0000001: hseg(x + 4, y0 + 19, w - 8)         # G
    h12 = ((hh + 11) % 12) + 1
    if h12 >= 10: digit(x0, 1)
    digit(x0 + 32, h12 % 10)
    d.rectangle([x0 + 67, y0 + 12, x0 + 72, y0 + 17], fill=L(9))
    d.rectangle([x0 + 67, y0 + 28, x0 + 72, y0 + 33], fill=L(9))
    digit(x0 + 80, mm // 10)
    digit(x0 + 112, mm % 10)

def analog_clock(d, cx, cy, r, hh, mm):
    """Redrawn 2026-08-31 (REFINEMENT.md §5 — "too basic for the size"): radial
    ticks at 12/3/6/9, small dots between, tapered two-segment hands with
    counterweight tails, a hub with a bright pin. Still NO bezel ring (a circle
    outline breaks the RLE run on every row) and NO second hand, ever (Adam
    2026-08-18); the minute hand SNAPS once a minute — 60 flushes/hour. Keep in
    lockstep with core Icons.analogClock()."""
    import math
    for i in range(12):
        a = math.radians(i * 30 - 90)
        if i % 3 == 0:                                          # cardinal: radial tick
            d.line([cx + (r - 8) * math.cos(a), cy + (r - 8) * math.sin(a),
                    cx + r * math.cos(a), cy + r * math.sin(a)], fill=L(6), width=3)
        else:
            px_, py_ = cx + r * math.cos(a), cy + r * math.sin(a)
            d.rectangle([px_ - 1, py_ - 1, px_ + 1, py_ + 1], fill=L(3))
    def hand(frac, length, w_near, w_far, tail):
        a = math.radians(frac * 360 - 90)
        mx, my = cx + length * 0.55 * math.cos(a), cy + length * 0.55 * math.sin(a)
        d.line([cx, cy, mx, my], fill=L(8), width=w_near)
        d.line([mx, my, cx + length * math.cos(a), cy + length * math.sin(a)], fill=L(8), width=w_far)
        d.line([cx, cy, cx - tail * math.cos(a), cy - tail * math.sin(a)], fill=L(8), width=w_near)
    hand(((hh % 12) + mm / 60) / 12, r * 0.55, 5, 3, r * 0.16)   # hour: short, wide
    hand(mm / 60, r * 0.86, 3, 2, r * 0.20)                      # minute: long, slim
    d.rectangle([cx - 3, cy - 3, cx + 3, cy + 3], fill=L(4))     # hub plate
    d.rectangle([cx - 1, cy - 1, cx + 1, cy + 1], fill=L(9))     # pin

def window_term_screen():
    im, d = new()
    chrome(d, "=damage:0", "TERM", "terminal", depth=2, at=5, op="tail")
    lines = ["$ ./venv/bin/pytest -q tests/compositor", "",
             "  tests/test_damage.py ........... 11 passed",
             "  tests/test_fid.py ..... 5 passed",
             "  tests/test_rle.py .............. 14 passed", "",
             "30 passed in 1.84s", "",
             "$ cargo build --release", "   Compiling damage-wm v0.1.0",
             "    Finished release [optimized] in 4m12s", "",
             "$ _"]
    for i, ln in enumerate(lines):
        text(d, (32, CY + PAD + i * 26 + 2), ln,
             HEAD if ln.startswith("$") else BODY, f_body)
    rail(d, 0.92, 70)
    return im

def silent_screen(notif=False):
    im, d = new()
    seven_seg_clock(d, W - 144 + 4, 2, 12, 59)        # digital, flush top-right (§1.5 rev 3)
    if notif:
        bx, by, bw, bh = 220, 214, 200, 56
        text(d, (bx + 8, by + 3), "SMS · MOM", L(8), f_small)
        d.rectangle([bx, by + 16, bx + bw - 1, by + 17], fill=L(2))
        text(d, (bx + 8, by + 26), "on my way", L(6), f_body)
    return im

# ---------------------------------------------------------------- output + pricing
def quantize(im):
    return im.point(lambda v: (min(15, (v + 8) // 17)) * 17)

def pack4(im):
    px = list(im.getdata()); out = bytearray()
    for i in range(0, len(px), 2):
        out.append(((px[i] // 17) << 4) | (px[i + 1] // 17))
    return bytes(out)

def green(im):
    g = Image.new("RGB", im.size)
    g.putdata([(int(v * 0.16), int(min(255, v * 1.05)), int(v * 0.34)) for v in im.getdata()])
    return g

set_family("Clear Sans", SYS_SCALE)
print(f"{'screen':22s} {'ink %':>7s} {'zlib(rle)':>10s} {'ratio':>7s} {'ms @11KB/s':>11s}")
print("-" * 62)
shots = [("main-active", main_screen(False)), ("main-resting", main_screen(True)),
         ("switcher", switcher_screen()), ("notification", notification_screen(False)),
         ("emergency", notification_screen(True)), ("window-list", window_list_screen()),
         ("window-doc", window_doc_screen()), ("silent", silent_screen()), ("silent-notif", silent_screen(notif=True))]
for name, im in shots:
    q = quantize(im)
    ink = sum(1 for v in q.getdata() if v > 0) / (W * H)
    b = len(zlib.compress(rle_nibble(pack4(q)), 6))
    green(q).save(f"/home/user/damagewm/design/shots/{name}.png")
    print(f"{name:22s} {ink:6.1%} {b:9,d} B {b/(W*H//2):7.3f} {b/11000*1000+176:9.0f} ms")


# (The 288-band / icon-rail comparison variants were removed 2026-08-18 once the
#  decision landed: full height for now, safe rect calibrated at first light, and
#  the ribbon retired. The rendered PNGs stay in shots/ as cmp-*.png for reference.)

# ---- low-battery pulse phases ----
strip = Image.new("L", (W, 7 * 40), BG); ds = ImageDraw.Draw(strip)
for ph in range(7):
    y0 = ph * 40
    for i, (tag, pct) in enumerate((("G", 87), ("R", 18), ("P", 62))):
        lv = batt_level(pct, ph if pct <= 20 else None)
        ds.text((40 + i * 60, y0 + 10), f"{tag}-{pct}%", fill=lv, font=f_batt)
    ds.text((260, y0 + 10), f"phase {ph}  ({FLASH_SEQ[ph]})", fill=L(3), font=f_tel)
green(quantize(strip)).save("/home/user/damagewm/design/shots/battery-pulse.png")

# ---- specimen sheets, ALL AT TRUE 1x PANEL SCALE ----
import json, subprocess
def subprocess_match(fam, bold=False):
    q = f"{fam}:style={'Bold' if bold else 'Regular'}"
    return subprocess.run(["fc-match", "-f", "%{file}", q],
                          capture_output=True, text=True).stdout.strip()
FONTS = json.load(open("/home/user/damagewm/design/fonts.json"))
LBL = ImageFont.truetype("/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf", 10)
SAMPLE = "build #482 · 0 failed"
print(f"\n{'specimen sheet':14s} {'faces':>6s}")
for cat, fams in FONTS.items():
    rowh = 30
    sheet = Image.new("L", (W, 24 + rowh * len(fams)), BG)
    ds = ImageDraw.Draw(sheet)
    ds.text((8, 6), f"{cat.upper()}  —  true 1x panel scale, UI sizes", fill=L(4), font=LBL)
    for r, (fam, (reg, bld)) in enumerate(fams.items()):
        y = 24 + r * rowh
        ds.text((8, y + 6), fam[:22], fill=L(4), font=LBL)
        try:
            fr, fb, fs_ = (ImageFont.truetype(reg, 17), ImageFont.truetype(bld, 12),
                           ImageFont.truetype(bld, 20))
        except Exception:
            # Bitmap faces (Terminus, Glass TTY) exist only at fixed pixel sizes and
            # cannot be scaled or antialiased — say so instead of dropping the row.
            ds.text((140, y + 7), "bitmap font — fixed size only, no AA", fill=L(3), font=LBL)
            continue
        ds.text((140, y + 7), "TERMINAL", fill=L(9), font=fb)
        ds.text((212, y + 3), SAMPLE, fill=L(8), font=fr)
        ds.text((452, y + 2), "Dune p.412", fill=L(12), font=fs_)
    green(quantize(sheet)).save(f"/home/user/damagewm/design/shots/specimen-{cat}.png")
    print(f"specimen-{cat:12s} {len(fams):5d}")

# see-through demo# ---- Main rendered in the top candidates, 1x, X-HEIGHT NORMALISED ----
# Comparing faces at the same nominal pt size is unfair: a face with a big x-height just
# looks larger. Normalise every candidate to DejaVu Sans's x-height so the comparison is
# about the LETTERFORMS, not about nominal sizing.
def xheight_ratio(path):
    f = ImageFont.truetype(path, 100)
    m = Image.new("L", (140, 160), 0)
    ImageDraw.Draw(m).text((10, 20), "x", fill=255, font=f)
    bb = m.getbbox()
    return (bb[3] - bb[1]) / 100.0 if bb else 0.5

TOP = ["DejaVu Sans", "B612", "Clear Sans", "Fira Sans", "IBM Plex Sans",
       "Source Sans 3", "Open Sans", "Roboto", "Ubuntu", "Cantarell"]
SANS = FONTS["sans"]
base_xh = xheight_ratio(SANS["DejaVu Sans"][0])
print(f"\n{'face':18s} {'x-ht':>6s} {'scale':>6s} {'ink %':>7s} {'zlib(rle)':>10s} {'vs DejaVu':>10s}")
print("-" * 64)
ref = None
for fam in TOP:
    if fam not in SANS: print(f"{fam:18s}  (not resolved)"); continue
    xh = xheight_ratio(SANS[fam][0])
    sc = base_xh / xh
    FAMILIES[fam] = SANS[fam]
    set_family(fam, sc)
    q = quantize(main_screen(False))
    ink = sum(1 for v in q.getdata() if v > 0) / (W * H)
    b = len(zlib.compress(rle_nibble(pack4(q)), 6))
    if ref is None: ref = b
    green(q).save(f"/home/user/damagewm/design/shots/main-{fam.split()[0].lower()}.png")
    print(f"{fam:18s} {xh:6.3f} {sc:6.2f} {ink:6.1%} {b:9,d} B {b/ref:9.2f}x")
set_family("Clear Sans", SYS_SCALE)

# ---- face comparison ACROSS windows, and the mixed-font demo ----
ALL = {}
for cat in FONTS.values(): ALL.update(cat)
ALL["Alegreya"] = (subprocess_match("Alegreya"), subprocess_match("Alegreya", True))

PICK = ["Fira Sans", "Alegreya Sans", "Nunito", "Humor Sans", "B612", "Clear Sans"]
SURFACES = [("main", main_screen, (False,)), ("list", window_list_screen, ()),
            ("doc", window_doc_screen, ())]
LBLF = ImageFont.truetype("/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf", 11)

def render_as(fam, fn, args=(), chrome_too=True):
    if fam not in ALL: return None
    xh = xheight_ratio(ALL[fam][0]) or 0.5
    FAMILIES[fam] = ALL[fam]
    (set_family if chrome_too else set_content_family)(fam, base_xh / xh)
    q = quantize(fn(*args))
    set_family("Clear Sans", SYS_SCALE)
    return q

print(f"\n{'face x surface':30s} {'ink %':>7s} {'bytes':>9s}")
print("-" * 50)
for key, fn, args in SURFACES:
    strips, CROP = [], (0, 140, W, 300)
    for fam in PICK:
        q = render_as(fam, fn, args)
        if q is None: continue
        ink = sum(1 for v in q.getdata() if v > 0) / (W * H)
        b = len(zlib.compress(rle_nibble(pack4(q)), 6))
        print(f"{fam + ' / ' + key:30s} {ink:6.1%} {b:8,d} B")
        strips.append((fam, green(q).crop(CROP)))
    ch = CROP[3] - CROP[1]
    sheet = Image.new("RGB", (W, (ch + 16) * len(strips)), (0, 0, 0))
    sd = ImageDraw.Draw(sheet)
    for i, (fam, img) in enumerate(strips):
        y = i * (ch + 16)
        sd.rectangle([0, y, W, y + 15], fill=(0, 26, 6))
        sd.text((6, y + 2), f"{fam}  —  {key}", fill=(120, 255, 150), font=LBLF)
        sheet.paste(img, (0, y + 16))
    sheet.save(f"/home/user/damagewm/design/shots/compare-{key}.png")

# ---- the mixed-font demo: four windows, each in a face chosen for its job ----
MIX = [("MAIN · system chrome + dashboard", "Clear Sans", main_screen, (False,)),
       ("MAIL · humanist sans, dense list", "Fira Sans", window_list_screen, ()),
       ("READER · literature serif", "Alegreya", window_doc_screen, ()),
       ("TERM · mono, column-aligned", "JetBrains Mono", window_term_screen, ())]
tiles = []
for cap, fam, fn, args in MIX:
    q = render_as(fam, fn, args, chrome_too=False)      # chrome held constant
    if q is None: q = quantize(fn(*args))
    tiles.append((cap, fam, green(q)))
demo = Image.new("RGB", (W, (H + 18) * len(tiles)), (0, 0, 0))
dd = ImageDraw.Draw(demo)
for i, (cap, fam, img) in enumerate(tiles):
    y = i * (H + 18)
    dd.rectangle([0, y, W, y + 17], fill=(0, 30, 8))
    dd.text((6, y + 3), f"{cap}  ->  content: {fam}  |  chrome: system face (fixed)", fill=(140, 255, 170), font=LBLF)
    demo.paste(img, (0, y + 18))
demo.save("/home/user/damagewm/design/shots/mixed-fonts.png")
print("\nwrote compare-main / compare-list / compare-doc / mixed-fonts (all true 1x)")
set_family("Clear Sans", SYS_SCALE)

# see-through demo: the resting shell over a synthetic "world"
world = Image.new("RGB", (W, H))
world.putdata([(60 + (x * 7 + y * 3) % 40, 55 + (x * 5) % 35, 50 + (y * 11) % 45)
               for y in range(H) for x in range(W)])
sh = green(quantize(main_screen(True)))
world.paste(Image.blend(world, sh, 1.0), (0, 0), sh.convert("L").point(lambda v: v * 3))
world.save("/home/user/damagewm/design/shots/see-through.png")
print("\nall output is TRUE 1x panel scale — green micro-LED simulation, no upscaling")
