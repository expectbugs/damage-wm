#!/usr/bin/env python3
"""Offline end-to-end verification of the CFW image we would flash.

Run this BEFORE any flashing conversation. It needs no network and no glasses:
everything it checks is already on disk. It proves four things —

  1. our local stock 2.2.6.10 is the exact image the patch sets pin as their base
  2. g2flash's 25 patches applied to it reproduce g2flash's own pinned output
  3. SybilSight's 28 patches reproduce BOTH their pinned output and the archived
     g2-2.2.6.11.bin, byte for byte
  4. the injected blob in each result carries NO Thumb-bit defect
     (openCFW's thumb_branch_audit — the class of bug that shipped once already)

...and prints the exact byte-level delta between the two CFW images, which should
be 15 bytes: three ASCII version digits plus their CRC fixups.

    python3 research/verify_cfw.py

Exit status is non-zero if anything fails to match.  See overview.md §3 / §9.
"""
import hashlib, json, pathlib, subprocess, sys, tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
STOCK = ROOT / "fws/2.2.6.10/e28738432d7b612d625331b00383149b.bin"
APPLY = ROOT / "reference/g2flash/patches/apply_patches.py"
AUDIT = ROOT / "reference/evenRealities-openCFW/g2/tools/thumb_branch_audit.py"
G2_JSON = ROOT / "reference/g2flash/patches/cfw_patches.json"
SY_DIR = ROOT / "fws/2.2.6.11-105032302d02"
SY_JSON = SY_DIR / "cfw_patches-2.2.6.11.json"
ARCHIVED = SY_DIR / "g2-2.2.6.11.bin"

# The injected blob is appended at the stock bundle's end; the audit needs its load address.
BLOB_OFFSET = 4301227          # == stock 2.2.6.10 bundle size
BLOB_BASE = 0x00794324         # == stock installedImageEnd

def sha(p):
    return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()

def check(label, got, want, fails):
    ok = got == want
    print(f"  {'PASS' if ok else 'FAIL'}  {label}")
    if not ok:
        print(f"          got  {got}\n          want {want}")
        fails.append(label)
    return ok

def main():
    fails = []
    g2 = json.loads(G2_JSON.read_text())
    sy = json.loads(SY_JSON.read_text())

    print("\n== 1. stock base ==")
    check("local stock 2.2.6.10 == patch-set pinned base", sha(STOCK), g2["base_sha256"], fails)
    check("both patch sets pin the same base", sy["base_sha256"], g2["base_sha256"], fails)

    with tempfile.TemporaryDirectory() as td:
        td = pathlib.Path(td)
        outs = {}
        print("\n== 2. rebuild from source ==")
        for tag, js in (("g2flash", G2_JSON), ("sybilsight", SY_JSON)):
            out = td / f"{tag}.bin"
            r = subprocess.run([sys.executable, str(APPLY), str(STOCK), str(js), str(out)],
                               capture_output=True, text=True)
            if r.returncode != 0:
                print(f"  FAIL  {tag} apply_patches: {r.stderr.strip()}"); fails.append(tag); continue
            outs[tag] = out
            print(f"  ok    {tag}: {r.stdout.strip().splitlines()[-1]}")

        print("\n== 3. hashes ==")
        check("g2flash rebuild == g2flash pinned output", sha(outs["g2flash"]), g2["output_sha256"], fails)
        check("sybilsight rebuild == sybilsight pinned output", sha(outs["sybilsight"]), sy["output_sha256"], fails)
        check("sybilsight rebuild == ARCHIVED g2-2.2.6.11.bin", sha(outs["sybilsight"]), sha(ARCHIVED), fails)
        check("sybilsight's claimed g2flash output hash == g2flash's actual",
              sy["g2flash_output_sha256"], g2["output_sha256"], fails)

        print("\n== 4. Thumb-bit audit of the injected blob ==")
        for tag, p in outs.items():
            blob = td / f"{tag}_blob.bin"
            blob.write_bytes(pathlib.Path(p).read_bytes()[BLOB_OFFSET:])
            r = subprocess.run([sys.executable, str(AUDIT), str(blob), "--base", hex(BLOB_BASE)],
                               capture_output=True, text=True)
            lines = [l for l in r.stdout.splitlines() if l.strip()]
            bad = [l for l in lines if "-> ARM" in l or "MISSING" in l.upper()]
            total = sum(1 for l in lines if l.rstrip().endswith("Thumb"))
            if bad:
                print(f"  FAIL  {tag}: {len(bad)} defect(s)"); [print("        ", l) for l in bad]
                fails.append(f"{tag} thumb")
            else:
                print(f"  PASS  {tag}: {total} constant interworking branches, all Thumb")

        print("\n== 5. delta between the two CFW images ==")
        a = pathlib.Path(outs["g2flash"]).read_bytes()
        b = pathlib.Path(outs["sybilsight"]).read_bytes()
        diff = [i for i in range(min(len(a), len(b))) if a[i] != b[i]]
        runs = []
        if diff:
            s = p = diff[0]
            for x in diff[1:]:
                if x != p + 1: runs.append((s, p)); s = x
                p = x
            runs.append((s, p))
        print(f"  {len(a):,} vs {len(b):,} bytes; {len(diff)} differing bytes in {len(runs)} run(s)")
        for s, e in runs:
            print(f"    @{s:<9} len={e-s+1}  {a[s:e+1]!r} -> {b[s:e+1]!r}")
        if len(diff) != 15:
            print("  NOTE: expected 15 differing bytes (3 version digits + CRC fixups).")

    print()
    if fails:
        print(f"RESULT: {len(fails)} CHECK(S) FAILED -- do not flash."); return 1
    print("RESULT: all checks passed. The archived image is reproducible from sources we hold.")
    print("        This does NOT authorise flashing -- see CLAUDE.md.")
    return 0

if __name__ == "__main__":
    sys.exit(main())
