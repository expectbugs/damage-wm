"""Reproduce the stock main app's start-up data initialisation (read-only on the image file).

Context for the reader: Damage is Adam's own window manager for his own G2 glasses; this reads the vendor firmware
image he holds for his own device (ordinary interoperability work) to answer one kind of question — the INITIAL value
of a word of RAM, which the image keeps packed. First use: `FIRMWARE.md` 6.1, the EvenHub page record at 0x20000B38
(2026-11-18). Checked by the app descriptors it recovers: each carries its own app id in its first word.

IAR's __iar_data_init3 at 0x005E42B4 walks a table of {relative pointer to an init routine, its parameters}:
table 0x0075D3C8..0x0075D410 (base = word@0x005E42D4 + 0x005E42BC + 0x18, end = word@0x005E42D8 + 0x005E42C2 + 0x16).
Two routines appear:
  0x005FA01E  zero init: {size, dest} pairs until size 0 (a dest with bit 0 set is r9-relative; none here)
  0x0043A11E  decompress: {src relative to the parameter word, packed length << 1 | r9-flag, dest}; per token byte t:
              literals = t & 3 (0: next byte + 3), copied as literals - 1 bytes; match = t >> 4 (15: next byte + 15);
              when match != 0: offset = next byte + (((t >> 2) & 3) == 3 ? next byte : (t >> 2) & 3) << 8, and
              match + 2 bytes are copied from dest - offset (0x0043A138-0x0043A192, read with research/fwread.py dis).
Usage: rwinit.py ADDR [LEN]   prints the initial bytes of RAM ADDR..ADDR+LEN
"""
import struct, sys, pathlib
ROOT = pathlib.Path(__file__).resolve().parent.parent
IMG = (ROOT / "fws/2.2.6.10/ota_s200_firmware_ota.bin").read_bytes()     # the stock image, as research/fwread.py reads it
BASE = 0x437FE0
def w(a): return struct.unpack_from("<I", IMG, a - BASE)[0]
def b(a): return IMG[a - BASE]

def table():
    base = (w(0x005E42D4) + 0x005E42BC + 0x18) & 0xFFFFFFFF
    end = (w(0x005E42D8) + 0x005E42C2 + 0x16) & 0xFFFFFFFF
    return base, end

def run():
    ram = {}                                             # address -> byte, every byte the init writes
    p, end = table()
    log = []
    while p != end:
        fn = (p + struct.unpack_from("<i", IMG, p - BASE)[0]) & 0xFFFFFFFF
        q = p + 4
        if fn == 0x005FA01F:                             # zero init
            while True:
                size = w(q); q += 4
                if size == 0: break
                dest = w(q); q += 4
                assert not dest & 1, "an r9-relative dest"
                for i in range(size): ram[dest + i] = 0
                log.append(f"zero  0x{dest:08x} +0x{size:x}")
            p = q
        elif fn == 0x0043A11F:                           # decompress
            src = (q + struct.unpack_from("<i", IMG, q - BASE)[0]) & 0xFFFFFFFF
            lw = w(q + 4); dest = w(q + 8)
            assert not lw & 1, "an r9-relative dest"
            s, e, d0 = src, src + (lw >> 1), dest
            while s != e:
                t = b(s); s += 1
                lit = t & 3
                if lit == 0: lit = b(s) + 3; s += 1
                m = t >> 4
                if m == 15: m = b(s) + 15; s += 1
                for _ in range(lit - 1):
                    ram[dest] = b(s); s += 1; dest += 1
                if m == 0: continue
                off = b(s); s += 1
                hi = (t >> 2) & 3
                if hi == 3: hi = b(s); s += 1
                off += hi << 8
                frm = dest - off
                for i in range(m + 2):
                    ram[dest] = ram[frm + i]; dest += 1
            log.append(f"lz    0x{d0:08x} +0x{dest - d0:x} from 0x{src:08x} ({lw >> 1} B packed)")
            p = q + 12
        else:
            raise SystemExit(f"unknown init routine 0x{fn:08x} at 0x{p:08x}")
    return ram, log

if __name__ == "__main__":
    ram, log = run()
    for l in log: print(l)
    a = int(sys.argv[1], 16); n = int(sys.argv[2], 0) if len(sys.argv) > 2 else 0x20
    print(f"0x{a:08x}: " + " ".join("%02x" % ram[a + i] if a + i in ram else "??" for i in range(n)))
