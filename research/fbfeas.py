"""Feasibility math for the ORIGINAL G2CC design under g2flash CFW.

Composes a representative full-screen 576x288 G2CC scene with real TrueType fonts
(the pre-pivot design), then encodes it the way the CFW actually does — RLE the
4bpp pixels, then deflate — and prices full frames vs dirty rects.
"""
import zlib
import numpy as np
from PIL import Image, ImageDraw, ImageFont

W, H = 576, 288        # NOTE: the real CFW canvas is 640x480 (overview.md §2). Kept at the
                       # G2CC scene size so these numbers stay comparable to the 2026-08-15 run.
FD = "/usr/share/fonts/dejavu"
# Throughput, corrected 2026-08-17 from the recovered captures (captures/imagestatus.log).
# The old 16571 B/s came from 232 B / 14 ms median inter-fragment gap — but that gap is the
# FAST mode of a trimodal distribution (0-1 ms / 12-17 ms / 56-61 ms). Measured end-to-end
# across multi-fragment image bursts is 7-13 KB/s; best instantaneous is 240 B / 13 ms.
THROUGHPUT_OPTIMISTIC = 16571.0   # old figure — median-gap derived, ~30-50% rosy
THROUGHPUT = 11000.0              # B/s, measured end-to-end (midpoint of the 7-13 KB/s band)
ACK_MS = 176.0         # median image-push ack, stock 2.2.2

# Text is rendered with PIL TrueType onto an "L" image, which ANTIALIASES by default, and
# to_gray4() maps 0-255 -> 0-15, so AA survives as real gray levels into the 4bpp pack.
# These numbers are therefore already AA-inclusive — which matters, because the CFW author
# notes zlib+RLE "doesn't play nice with antialiased fonts" (overview.md §5).


def scene(sel=1, body_variant=0):
    """A realistic G2CC screen: title bar, cursive-ish menu, content pane, status."""
    im = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(im)
    head = ImageFont.truetype(f"{FD}/DejaVuSans-Bold.ttf", 16)
    body = ImageFont.truetype(f"{FD}/DejaVuSans.ttf", 14)
    small = ImageFont.truetype(f"{FD}/DejaVuSans.ttf", 12)
    mono = ImageFont.truetype(f"{FD}/DejaVuSansMono.ttf", 13)

    # title bar (33px) + clock cutout
    d.rectangle([0, 0, W - 1, 32], outline=200)
    d.text((6, 8), "Claude Code · G2CC · 3/3", font=head, fill=255)
    d.text((W - 96, 9), "1:04 PM", font=body, fill=230)

    # left menu column (96px), selection highlight
    d.rectangle([0, 33, 95, 254], outline=160)
    for i, item in enumerate(["Main", "Code", "Mail", "Music", "Games"]):
        y = 42 + i * 26
        if i == sel:
            d.rectangle([3, y - 3, 92, y + 19], fill=90)
            d.polygon([(6, y + 4), (6, y + 14), (13, y + 9)], fill=255)
        d.text((18, y), item, font=body, fill=255 if i == sel else 190)

    # content pane
    lines = [
        "The renderer composes the whole scene server-side,",
        "then blits one framebuffer. Custom fonts, real",
        "kerning, arbitrary layout — no firmware containers.",
        "",
        "  def compose(scene):",
        "      return rasterize(scene, fonts)",
        "",
        "Dirty rects mean a cursor move costs a few hundred",
        "bytes, not a full-screen repush.",
    ]
    y = 40
    for i, ln in enumerate(lines):
        f = mono if ln.startswith("  ") else body
        shade = 255 if i == body_variant else 215
        d.text((104, y), ln, font=f, fill=shade)
        y += 21

    # status bar
    d.rectangle([0, 255, W - 1, 287], outline=200)
    d.text((6, 263), "● beardos · 1 cc · ⚠2", font=small, fill=230)
    return im


def to_gray4(im):
    return (np.array(im, dtype=np.uint16) * 15 // 255).astype(np.uint8)


def pack4(a):
    h, w = a.shape
    flat = a.reshape(-1)
    if (w * h) % 2:
        flat = np.append(flat, 0)
    return ((flat[0::2] << 4) | (flat[1::2] & 0xF)).astype(np.uint8).tobytes()


def rle_bytelevel(buf):
    """DEPRECATED byte-level RLE — what this script used before 2026-08-17.

    Kept only so the old numbers can be reproduced. It is NOT what the firmware does: it
    runs over PACKED BYTES (2 px each) and always spends 2 bytes per run, which is
    materially wrong on antialiased text, where runs are short and the firmware's 1-byte
    short token wins. Use rle_nibble().
    """
    out = bytearray()
    i, n = 0, len(buf)
    while i < n:
        c = buf[i]
        run = 1
        while i + run < n and buf[i + run] == c and run < 255:
            run += 1
        out += bytes((run, c))
        i += run
    return bytes(out)


def rle_nibble(buf):
    """The ACTUAL g2flash RLE, per patches/zlib_glue.c (modes 3 and 6 only).

    Runs over the pixel NIBBLES of the tightly packed rows in wire order (high nibble =
    left pixel), including the pad nibble that ends an odd-width row. Runs may cross row
    boundaries. Token format — low nibble is ALWAYS the 4bpp colour, high nibble is the
    repeat count, and 0 escapes to the wider forms:

        [cnt4|color4]                  cnt 1..15      (1 byte)
        [0|color4][cnt8]               cnt 1..255     (2 bytes)
        [0|color4][0][cntLo][cntHi]    cnt 1..65535   (4 bytes, little-endian)

    65535 is the longest single run; an encoder splits anything longer into consecutive
    tokens. The firmware inflates, then RLE-decodes this straight into the shadow.
    """
    nib = []
    for b in buf:
        nib.append(b >> 4)
        nib.append(b & 0x0F)
    out = bytearray()
    i, n = 0, len(nib)
    while i < n:
        c = nib[i]
        run = 1
        while i + run < n and nib[i + run] == c and run < 65535:
            run += 1
        i += run
        while run > 0:
            take = min(run, 65535)
            if take <= 15:
                out.append((take << 4) | c)
            elif take <= 255:
                out.append(c)            # high nibble 0 = escape
                out.append(take)
            else:
                out.append(c)
                out.append(0)            # second escape = 16-bit count
                out.append(take & 0xFF)
                out.append((take >> 8) & 0xFF)
            run -= take
    return bytes(out)


def cfw_encode(packed):
    """zlib(rle(pixels)) — the firmware deflates the RLE STREAM, not the raw pixels."""
    return zlib.compress(rle_nibble(packed), 6)


def price(label, nbytes, note=""):
    ms = nbytes / THROUGHPUT * 1000 + ACK_MS
    print(f"{label:<42} {nbytes:>7,} B   {ms:>7.0f} ms   {1000/ms:>5.1f} fps  {note}")


def main():
    a = to_gray4(scene(sel=1))
    packed = pack4(a)
    print(f"full screen {W}x{H} @4bpp\n")
    print("--- what the ORIGINAL design cost on stock firmware ---")
    price("raw 4bpp, one full-screen blit", len(packed), "(exceeds stock 288x144 cap)")
    tile = pack4(to_gray4(scene().crop((96, 33, 336, 144))))
    print(f"   ...so stock forced 4x 240x111 tiles, ack-gated:")
    per = len(tile) + 118
    tot = 4 * (per / THROUGHPUT * 1000 + 4 * ACK_MS)
    print(f"   4 tiles x {per:,} B, 4 chunks each -> {tot:,.0f} ms  ({1000/tot:.2f} fps)\n")

    print("--- the same screen under g2flash CFW (zlib(rle(px))) ---")
    enc = cfw_encode(packed)
    price("full-screen keyframe", len(enc), f"({len(enc)/len(packed):.3f}x of raw)")

    # dirty rect: move the menu selection one row
    b = to_gray4(scene(sel=2))
    diff = np.argwhere(a != b)
    if len(diff):
        y0, x0 = diff.min(0)
        y1, x1 = diff.max(0) + 1
        sub = pack4(np.ascontiguousarray(b[y0:y1, x0:x1]))
        e = cfw_encode(sub)
        price(f"dirty rect: menu cursor move", len(e),
              f"[{x1-x0}x{y1-y0} box]")

    # dirty rect: one content line changes brightness (typical text update)
    c = to_gray4(scene(sel=1, body_variant=2))
    diff = np.argwhere(a != c)
    if len(diff):
        y0, x0 = diff.min(0)
        y1, x1 = diff.max(0) + 1
        sub = pack4(np.ascontiguousarray(c[y0:y1, x0:x1]))
        e = cfw_encode(sub)
        price(f"dirty rect: one text line repaint", len(e),
              f"[{x1-x0}x{y1-y0} box]")

    print("\n--- stock LZ4 full-screen, for comparison (no CFW) ---")
    import subprocess
    p = subprocess.run(["lz4", "-1", "-c", "--no-frame-crc", "-"],
                       input=packed, capture_output=True)
    price("full screen, stock CompressMode=2", len(p.stdout),
          "(but stock caps a container at 288x144)")


main()
