#!/usr/bin/env python3
"""Read the stock 2.2.6.10 main app offline: disassembly, the decompile corpus, literal
references, call sites and strings — the instruction-level reads `FORK.md` Phase 0 rests on.

Context: Damage's firmware fork (`FORK.md`) needs facts about the stock image its patches
sit in. The openCFW Ghidra corpus is the index, but it can hide arguments and misses whole
functions (`HANDOFF.md` §49.2), so every load-bearing fact is checked here against the
instructions. Read-only: nothing is written except a cached ELF wrapper of the image.

Addresses are RUN addresses (file offset + 0x437FE0), the space `patch_compress.py`,
`CLAIMS.md` and the corpus use.

    python3 research/fwread.py dis 0x592dea 0x592ea0     # Thumb disassembly, run addresses
    python3 research/fwread.py fn 473c44 474066          # the corpus decompile of FUN_<addr>
    python3 research/fwread.py word 0x005938e0           # the 32-bit word stored at an address
    python3 research/fwread.py refs 0x20074530           # where a 32-bit value sits in the image (literal pools)
    python3 research/fwread.py calls 0x458e48            # BL call sites that target an address
    python3 research/fwread.py strings 'logger|ble_log'  # printable strings matching a regex, with addresses
    python3 research/fwread.py owner 0x45a1c8            # the corpus function whose range holds an address
    python3 research/fwread.py sha 475b14 46f258         # do our image's bytes for FUN_<addr> hash as the corpus's header says? (SAME/DIFFERENT)

Needs llvm-objdump / llvm-objcopy (LLVM 21+ found under /usr/lib/llvm) for `dis`.
"""
import bisect, glob, json, os, pathlib, re, struct, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
IMAGE = ROOT / "fws/2.2.6.10/ota_s200_firmware_ota.bin"
CORPUS = ROOT / "reference/evenRealities-openCFW/g2/research/corpus/apollo-main/ghidra/decomp"
BASE = 0x437FE0                      # run address of file offset 0 (openCFW's convention)
CACHE = pathlib.Path(os.environ.get("XDG_CACHE_HOME", pathlib.Path.home() / ".cache")) / "damage-fwread"

def image():
    return IMAGE.read_bytes()

def llvm(tool):
    found = sorted(glob.glob(f"/usr/lib/llvm/*/bin/{tool}"), key=lambda p: int(p.split("/")[4]))
    if found: return found[-1]
    sys.exit(f"{tool} not found under /usr/lib/llvm")

def elf():
    CACHE.mkdir(parents=True, exist_ok=True)
    out = CACHE / "main.elf"
    if not out.exists() or out.stat().st_mtime < IMAGE.stat().st_mtime:
        tmp = CACHE / "main.bin"
        tmp.write_bytes(image())
        subprocess.run([llvm("llvm-objcopy"), "-I", "binary", "-O", "elf32-littlearm",
                        "--rename-section=.data=.text,alloc,load,readonly,code,contents",
                        "main.bin", "main.elf"], check=True, cwd=CACHE)
    return out

def num(s): return int(s, 16) if not s.startswith("0x") else int(s, 0)

def dis(start, stop):
    r = subprocess.run([llvm("llvm-objdump"), "-d", "--triple=thumbv8.1m.main",
                        f"--start-address={start - BASE}", f"--stop-address={stop - BASE}", str(elf())],
                       capture_output=True, text=True, check=True)
    for line in r.stdout.splitlines()[6:]:
        m = re.match(r"\s*([0-9a-f]+):(.*)", line)
        if not m: continue
        rest = re.sub(r"(@ )?0x([0-9a-f]+) <_binary_\w+_start\+0x[0-9a-f]+>",
                      lambda k: (k.group(1) or "") + "0x%08x" % (int(k.group(2), 16) + BASE), m.group(2))
        print("%08x:%s" % (int(m.group(1), 16) + BASE, rest))

def fn(addrs):
    for a in addrs:
        head = "/* FUN 0x%08x " % num(a)
        hit = False
        for f in sorted((CORPUS / "bundles").glob("*.c")):
            printing = False
            for line in f.open():
                if line.startswith(head): printing = hit = True
                elif printing and line.startswith("/* FUN 0x"): break
                if printing: print(line, end="")
            if hit: break
        if not hit: print(f"FUN_{num(a):08x}: not in the corpus function list (Ghidra may have missed it: use dis)")

def funcs():
    rows = []
    for line in (CORPUS / "functions.jsonl").open():
        j = json.loads(line)
        for lo, hi in j["ranges"]: rows.append((int(lo, 16), int(hi, 16), j["name"]))
    rows.sort()
    return rows

def owner(addr, rows=None):
    rows = rows or funcs()
    i = bisect.bisect_right([r[0] for r in rows], addr) - 1
    if i >= 0 and rows[i][0] <= addr <= rows[i][1]: return rows[i][2]
    return f"- (nearest before: {rows[i][2]})" if i >= 0 else "-"

def refs(value):
    d = image()
    rows = funcs()
    for off in range(0, len(d) - 3, 2):
        if struct.unpack_from("<I", d, off)[0] == value:
            print("0x%08x  %s" % (off + BASE, owner(off + BASE, rows)))

def calls(target):
    d = image()
    for i in range(0, len(d) - 3, 2):
        hw1 = d[i] | d[i + 1] << 8; hw2 = d[i + 2] | d[i + 3] << 8
        if (hw1 & 0xF800) != 0xF000 or (hw2 & 0xD000) != 0xD000: continue
        s = (hw1 >> 10) & 1; j1 = (hw2 >> 13) & 1; j2 = (hw2 >> 11) & 1
        off = (s << 24) | ((~(j1 ^ s) & 1) << 23) | ((~(j2 ^ s) & 1) << 22) | ((hw1 & 0x3FF) << 12) | ((hw2 & 0x7FF) << 1)
        if s: off -= 1 << 25
        if i + BASE + 4 + off == target: print("0x%08x" % (i + BASE))

def strings(pattern):
    d = image()
    rx = re.compile(pattern.encode(), re.I)
    for m in re.finditer(rb"[\x20-\x7e]{5,}", d):
        if rx.search(m.group(0)): print("0x%08x  %s" % (m.start() + BASE, m.group(0).decode("latin1")[:160]))

def sha(addrs):
    """The corpus writes `/* FUN 0x... FUN_... bytes=N sha256=... */` over each decompile; check that
    the N bytes at that address in OUR image hash the same — a decompile is only evidence about the
    bytes it was made from (2026-09-14: three sender functions checked before a patch leaned on them)."""
    import hashlib
    d = image()
    for a in addrs:
        head = "/* FUN 0x%08x " % num(a)
        found = None
        for f in sorted((CORPUS / "bundles").glob("*.c")):
            for line in f.open():
                if line.startswith(head):
                    m = re.search(r"bytes=(\d+) sha256=([0-9a-f]{64})", line)
                    if m: found = (int(m.group(1)), m.group(2))
                    break
            if found: break
        if not found: print(f"FUN_{num(a):08x}: not in the corpus function list"); continue
        n, want = found
        got = hashlib.sha256(d[num(a) - BASE:num(a) - BASE + n]).hexdigest()
        print(f"FUN_{num(a):08x}: {n} bytes {'SAME' if got == want else 'DIFFERENT'} (image {got[:16]}…, corpus {want[:16]}…)")

def main(argv):
    if not argv or argv[0] in ("-h", "--help"): print(__doc__); return 0
    cmd, args = argv[0], argv[1:]
    if cmd == "dis": dis(num(args[0]), num(args[1]))
    elif cmd == "fn": fn(args)
    elif cmd == "word": print("0x%08x" % struct.unpack_from("<I", image(), num(args[0]) - BASE)[0])
    elif cmd == "refs": refs(num(args[0]))
    elif cmd == "calls": calls(num(args[0]))
    elif cmd == "strings": strings(args[0])
    elif cmd == "owner": print(owner(num(args[0])))
    elif cmd == "sha": sha(args)
    else: print(__doc__); return 2
    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
