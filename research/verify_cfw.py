#!/usr/bin/env python3
"""Offline end-to-end verification of the CFW image we would flash.

Run this BEFORE any flashing conversation. It needs no network and no glasses:
everything it checks is already on disk. It proves five things —

  1. our local stock 2.2.6.10 is the exact image both patch sets pin as their base
  2. g2flash's patches applied to it reproduce g2flash's own pinned output
  3. SybilSight's 28 patches reproduce BOTH their pinned output and the archived
     g2-2.2.6.11.bin, byte for byte
  4. the injected blob in each result carries NO Thumb-bit defect
     (openCFW's thumb_branch_audit — the class of bug that shipped once already)
  5. each image's main app FITS MRAM with its preamble length bumped to match —
     the one brick path with no BLE recovery — checked by calling the flasher's
     own guard rather than a reimplementation of it

The two CFW images are **different builds**, not two spellings of one: SybilSight
forked g2flash at 877c8d9 and has since removed custom-firmware support, while
g2flash went on to a5d1c31 (the texture cache). Section 3 reports that fork
distance; it is not a failure. What must hold is that each patch set still
reproduces its own pinned output exactly.

    python3 research/verify_cfw.py

Exit status is non-zero if anything fails to match.  See overview.md §3 / §9.
"""
import hashlib, json, pathlib, struct, subprocess, sys, tempfile

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

FLASHER = ROOT / "reference/g2flash/g2flash.py"

def sha(p):
    return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()

def _flasher():
    """Import g2flash as a module. Parsing and validating an image on disk touches
    no radio; nothing below calls anything that opens a transport."""
    import importlib.util
    spec = importlib.util.spec_from_file_location("g2flash_mod", FLASHER)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod

def mram_report(img):
    """Run the flasher's OWN main-app guard and report its numbers.

    This is the single brick path that is not recoverable over BLE: the bootloader
    programs `preamble[0]&0xFFFFFF` bytes to `preamble[0x14]` with no bounds check,
    so an enlarged image whose preamble length was not bumped to match overruns
    into the OTA flag / NV band or off the end of MRAM. Every CFW image IS
    enlarged, so this is checked here rather than trusted -- and it is checked with
    the flasher's code, not a reimplementation, so the two can never disagree.
    """
    m = _flasher()
    segs = m.validate_firmware(img)                  # raises on a malformed bundle
    m.check_mainapp_fits_mram(img, segs)             # raises on any of the three faults
    s = next(x for x in segs if x['fn'] == m.REQUIRED_SEGMENT)
    pre = img[s['off'] + 128:s['off'] + 128 + m.APP_PREAMBLE]
    plen = struct.unpack_from('<I', pre, 0)[0] & 0xFFFFFF
    end = m.APP_LOAD_ADDR + s['ps'] - m.APP_PREAMBLE
    head = m.OTA_FLAG_ADDR - end
    return True, (f"{len(segs)} segments, payload {s['ps']:,} B, preamble length matches "
                  f"({plen:,}), ends 0x{end:08x}, {head:,} B ({head/1024:.0f} KB) below the "
                  f"OTA flag at 0x{m.OTA_FLAG_ADDR:08x}")

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
        # SybilSight pins the g2flash output it was forked from. That pin goes stale
        # every time g2flash ships, and g2flash has since moved on (a5d1c31 added the
        # texture cache; SybilSight has meanwhile REMOVED custom-firmware support
        # entirely). Divergence here is expected and is NOT a defect -- what matters
        # is that each patch set still reproduces ITS OWN pinned output, checked
        # above. Report the relationship; do not fail on it.
        if sy["g2flash_output_sha256"] == g2["output_sha256"]:
            print("  ok    sybilsight is forked from the CURRENT g2flash output")
        else:
            print("  note  sybilsight is forked from an OLDER g2flash output")
            print(f"          sybilsight tracks {sy['g2flash_output_sha256'][:16]}...")
            print(f"          g2flash now emits {g2['output_sha256'][:16]}...")
            print("          -> the two CFW images are different builds; section 5's")
            print("             delta is a fork distance, not a version-string tweak.")

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
        same_fork = sy["g2flash_output_sha256"] == g2["output_sha256"]
        print(f"  {len(a):,} vs {len(b):,} bytes; {len(diff)} differing bytes")
        if same_fork:
            # Same fork point: the only legitimate difference is the version string.
            runs = []
            if diff:
                s = p = diff[0]
                for x in diff[1:]:
                    if x != p + 1: runs.append((s, p)); s = x
                    p = x
                runs.append((s, p))
            for s, e in runs:
                print(f"    @{s:<9} len={e-s+1}  {a[s:e+1]!r} -> {b[s:e+1]!r}")
            if len(diff) != 15:
                print("  NOTE: expected 15 differing bytes (3 version digits + CRC fixups).")
        else:
            print("  (byte-level dump suppressed: different fork points, so a large delta is")
            print("   expected and listing it says nothing. Re-enable it if the pins realign.)")

        print("\n== 6. main-app fits MRAM (the one unbounded-program brick path) ==")
        for tag, p in outs.items():
            try:
                ok, detail = mram_report(pathlib.Path(p).read_bytes())
            except Exception as exc:                       # noqa: BLE001 - report, don't mask
                print(f"  FAIL  {tag}: {exc}"); fails.append(f"{tag} mram"); continue
            print(f"  {'PASS' if ok else 'FAIL'}  {tag}: {detail}")
            if not ok: fails.append(f"{tag} mram")

    print()
    if fails:
        print(f"RESULT: {len(fails)} CHECK(S) FAILED -- do not flash."); return 1
    print("RESULT: all checks passed. The archived image is reproducible from sources we hold.")
    print("        This does NOT authorise flashing -- see CLAUDE.md.")
    return 0

if __name__ == "__main__":
    sys.exit(main())
