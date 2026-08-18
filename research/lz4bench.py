"""Measure LZ4 ratio on G2CC's REAL 240x111 tile payloads (packed 4bpp gray4).

Models exactly what G2Renderer puts on the wire: a 4bpp BMP body. Compresses the
packed pixel data and reports the ack-gated chunk count at MAX_IMAGE_CHUNK=4096.
"""
import subprocess, sys, os, glob
import numpy as np
from PIL import Image

TILE_W, TILE_H = 240, 111
BMP_HEADER = 118          # BITMAPFILEHEADER+INFOHEADER+16-entry gray palette
CHUNK = 4096              # DisplayProto.MAX_IMAGE_CHUNK
ACK_MS = 176              # median image-push ack latency (G2_BLE_PROTOCOL §9)


def to_gray4_packed(img, w=TILE_W, h=TILE_H, dither=False):
    """Grayscale -> 16 levels -> packed 4bpp, 4-byte-aligned rows (BMP rule)."""
    g = img.convert("L").resize((w, h), Image.LANCZOS)
    if dither:
        # Floyd-Steinberg to 16 levels via a 16-entry palette
        pal = Image.new("P", (1, 1))
        pal.putpalette(sum(([i * 17] * 3 for i in range(16)), []))
        g = g.quantize(palette=pal, dither=Image.FLOYDSTEINBERG)
        a = np.array(g, dtype=np.uint8)
    else:
        a = (np.array(g, dtype=np.uint16) * 15 // 255).astype(np.uint8)
    stride = ((w + 1) // 2 + 3) // 4 * 4
    out = bytearray()
    for row in a:
        packed = bytearray(stride)
        for x in range(0, w - 1, 2):
            packed[x // 2] = (row[x] << 4) | (row[x + 1] & 0xF)
        if w % 2:
            packed[(w - 1) // 2] = row[w - 1] << 4
        out += packed
    return bytes(out)


def lz4_size(data, hc=False):
    p = subprocess.run(["lz4", "-9" if hc else "-1", "-c", "--no-frame-crc", "-"],
                       input=data, capture_output=True)
    return len(p.stdout)


def chunks(n):
    return (n + CHUNK - 1) // CHUNK


def report(label, raw_bytes, comp_fast, comp_hc):
    raw = len(raw_bytes)
    tot_r, tot_f = raw + BMP_HEADER, comp_fast + BMP_HEADER
    cr, cf = chunks(tot_r), chunks(tot_f)
    print(f"{label:<34} raw {tot_r:>6}B ({cr} chunk{'s' if cr>1 else ''}, "
          f"{cr*ACK_MS:>4}ms)  lz4 {tot_f:>6}B ({comp_fast/raw:4.2f}x -> {cf} chunk, "
          f"{cf*ACK_MS:>4}ms)  lz4hc {comp_hc+BMP_HEADER:>6}B ({comp_hc/raw:4.2f}x)"
          f"   speedup {cr/cf:.1f}x")
    return cr, cf


def main():
    rows = []
    print(f"=== per-tile, {TILE_W}x{TILE_H} packed 4bpp, chunk={CHUNK}, ack={ACK_MS}ms ===\n")

    for path in sys.argv[1:]:
        img = Image.open(path)
        for dith, tag in ((False, ""), (True, " +FS-dither")):
            d = to_gray4_packed(img, dither=dith)
            rows.append(report(os.path.basename(path)[:28] + tag, d,
                               lz4_size(d), lz4_size(d, hc=True)))

    # synthetic extremes for the envelope
    for label, arr in (
        ("SYNTH flat black", np.zeros((TILE_H, TILE_W), np.uint8)),
        ("SYNTH UI: text-like bands", None),
        ("SYNTH random noise (worst case)", None),
    ):
        if label.endswith("bands"):
            arr = np.zeros((TILE_H, TILE_W), np.uint8)
            for i in range(0, TILE_H, 14):
                arr[i:i + 7, 8:TILE_W - 8] = 15
        elif "noise" in label:
            rng = np.random.default_rng(0)
            arr = rng.integers(0, 256, (TILE_H, TILE_W), dtype=np.uint8)
        im = Image.fromarray(arr if arr.max() > 15 else (arr * 17).astype(np.uint8))
        d = to_gray4_packed(im)
        rows.append(report(label, d, lz4_size(d), lz4_size(d, hc=True)))


if __name__ == "__main__":
    main()
