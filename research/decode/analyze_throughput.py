#!/usr/bin/env python3
"""Reproduce the throughput analysis behind overview.md §5.1.

Every number §5.1 asserts is produced here, from captures/ alone. Run it if you want to
re-check the claims, extend the analysis, or hand a result to the CFW author.

    python3 research/decode/analyze_throughput.py

What it establishes (and what it REFUTES):
  * fragment-gap distribution is trimodal: 0-1 / 12-17 / 56-61 ms
  * end-to-end throughput across multi-fragment bursts: 7-13 KB/s (NOT the old 16.6)
  * REFUTED: one-write-per-connection-event  -> back-to-back 0-1 ms gaps exist
  * REFUTED: controller buffer-credit exhaustion -> pool is 12, peak outstanding 4
  * REFUTED: 3-link radio contention -> L lens + ring are silent during stalls
  * REFUTED: ack-gating explains intra-image stalls -> the stalls contain nothing
  * SHOWN: half of >=40 ms stalls resume with ZERO outstanding => host isn't feeding a
    ready radio; the bottleneck is above the controller and below the app's ack logic
  * SHOWN: no LE_Conn_Update is ever issued for handle 65 (R lens, all display traffic),
    while handles 64 and 66 both get explicit ones

KNOWN GAP: handle 65's connection setup is outside both capture windows, so its actual
interval is unrecoverable here. A recapture must start BTSnoop BEFORE connecting.
"""
import struct, pathlib, sys
from collections import Counter

CAP = pathlib.Path(__file__).resolve().parent.parent.parent / "captures"
DISPLAY_HANDLE = 65          # R lens; carries all e0-XX display traffic
WRITE_ATT_HANDLE = 0x0842    # ...e5401, the command-channel write characteristic

def records(path):
    b = pathlib.Path(path).read_bytes(); i = 16
    while i + 24 <= len(b):
        o, il, fl, dr, ts = struct.unpack(">IIIIq", b[i:i+24])
        yield ts, fl, b[i+24:i+24+il]; i += 24 + il

def conn_params(path):
    """LE Connection Complete / Connection Update Complete events, and update COMMANDS."""
    out = []
    for ts, fl, p in records(path):
        if not p: continue
        if p[0] == 0x04 and len(p) > 4 and p[1] == 0x3E:
            sub = p[3]
            if sub == 0x01 and len(p) >= 23:
                h = struct.unpack("<H", p[5:7])[0] & 0xFFF
                iv, lat, to = struct.unpack("<HHH", p[17:23])
                out.append((ts, h, "CONNECT", iv*1.25, lat, to*10))
            elif sub == 0x03 and len(p) >= 13:
                h = struct.unpack("<H", p[5:7])[0] & 0xFFF
                iv, lat, to = struct.unpack("<HHH", p[7:13])
                out.append((ts, h, "UPDATE", iv*1.25, lat, to*10))
        elif p[0] == 0x01 and len(p) >= 17:
            op = struct.unpack("<H", p[1:3])[0]
            if op == 0x2013:                      # LE Connection Update (command)
                h = struct.unpack("<H", p[4:6])[0] & 0xFFF
                imin, imax, lat, to = struct.unpack("<HHHH", p[6:14])
                out.append((ts, h, "CMD_UPDATE", (imin*1.25, imax*1.25), lat, to*10))
    return out

def buffer_size(path):
    for ts, fl, p in records(path):
        if p[:1] == b"\x04" and len(p) > 6 and p[1] == 0x0E:
            if struct.unpack("<H", p[4:6])[0] == 0x1005 and len(p) >= 14:
                aclmax, scomax, aclnum, sconum = struct.unpack("<HBHH", p[7:14])
                return aclmax, aclnum
    return None, None

def events(path):
    """Unified timeline: ATT writes/notifies plus Number-Of-Completed-Packets."""
    ev = []
    for ts, fl, p in records(path):
        if not p: continue
        if p[0] == 0x02 and len(p) >= 9:
            h = struct.unpack("<H", p[1:3])[0] & 0xFFF
            l2, cid = struct.unpack("<HH", p[5:9])
            tx = (fl & 1) == 0
            att = struct.unpack("<H", p[10:12])[0] if len(p) >= 12 else 0
            ev.append((ts, "TX" if tx else "RX", h, cid, len(p)-9, att, p[9] if len(p) > 9 else 0))
        elif p[0] == 0x04 and p[1] == 0x13 and len(p) >= 4:
            for k in range(p[3]):
                off = 4 + k*4
                if off + 4 <= len(p):
                    hh, cnt = struct.unpack("<HH", p[off:off+4])
                    ev.append((ts, "NOCP", hh & 0xFFF, 0, 0, 0, cnt))
    ev.sort(); return ev

def main():
    seg = CAP / "imagestatus.log"
    if not seg.exists():
        print(f"missing {seg} -- see captures/README.md"); return 1
    print(f"== connection parameters ({seg.name} + .last) ==")
    for f in (CAP/"imagestatus.last", seg):
        for ts, h, kind, iv, lat, to in conn_params(f):
            print(f"   h={h:<4} {kind:<11} interval={iv} lat={lat} sup={to}ms")
    seen = {h for f in (CAP/"imagestatus.last", seg) for _, h, _, _, _, _ in conn_params(f)}
    print(f"   handles with ANY parameter event: {sorted(seen)}")
    print(f"   -> handle {DISPLAY_HANDLE} (display link) {'HAS' if DISPLAY_HANDLE in seen else 'has NO'} "
          f"parameter negotiation in this corpus")

    amax, anum = buffer_size(seg)
    print(f"\n== controller ACL buffers ==\n   max={amax}B  packets={anum}")

    ev = events(seg)
    frag = [(i, e) for i, e in enumerate(ev)
            if e[1] == "TX" and e[2] == DISPLAY_HANDLE and e[3] == 4
            and e[5] == WRITE_ATT_HANDLE and e[4] >= 240]
    print(f"\n== full-size image fragments: {len(frag)} ==")
    gaps = [((frag[a+1][1][0]-frag[a][1][0])/1000.0, frag[a][0], frag[a+1][0])
            for a in range(len(frag)-1) if (frag[a+1][1][0]-frag[a][1][0])/1000.0 < 200]
    gs = sorted(g for g, _, _ in gaps)
    if gs:
        q = lambda p: gs[int(len(gs)*p)]
        print(f"   gaps <200ms: n={len(gs)} p10={q(.1):.1f} p25={q(.25):.1f} p50={q(.5):.1f} "
              f"p75={q(.75):.1f} p90={q(.9):.1f} ms")
    c = Counter(min(int(g//2)*2, 60) for g, _, _ in gaps)
    print("   histogram (2ms bins, 60+ collapsed):")
    for k in sorted(c): print(f"     {k:3d}{'+' if k==60 else '-'+str(k+1):>4} ms {'#'*min(c[k],50)} {c[k]}")

    slow = [(g, i0, i1) for g, i0, i1 in gaps if g >= 40]
    inner = Counter()
    for g, i0, i1 in slow:
        if i1 == i0 + 1: inner["(nothing at all)"] += 1
        for j in range(i0+1, i1):
            t = ev[j]
            inner["NOCP" if t[1] == "NOCP" else f"{t[1]} h{t[2]}"] += 1
    print(f"\n== what is inside the {len(slow)} slow (>=40ms) fragment gaps ==")
    for k, v in inner.most_common(8): print(f"   {v:4d}  {k}")

    out = 0; peak = 0; resume = Counter(); prev = None
    for ts, kind, h, cid, ln, att, extra in ev:
        if h != DISPLAY_HANDLE: continue
        if kind == "TX":
            if prev is not None and (ts-prev)/1000.0 >= 40: resume[out] += 1
            out += 1; peak = max(peak, out); prev = ts
        elif kind == "NOCP": out = max(0, out - extra)
    tot = sum(resume.values())
    print(f"\n== outstanding-packet state ==\n   peak outstanding = {peak} (pool = {anum})")
    print(f"   outstanding when resuming after a >=40ms stall: {dict(sorted(resume.items()))}")
    if tot: print(f"   -> {resume.get(0,0)}/{tot} ({100*resume.get(0,0)//tot}%) resumed with the radio IDLE")

    bursts = []; cur = [frag[0][1]] if frag else []
    for a in range(1, len(frag)):
        if (frag[a][1][0]-frag[a-1][1][0])/1000.0 > 150: bursts.append(cur); cur = []
        cur.append(frag[a][1])
    if cur: bursts.append(cur)
    big = [b for b in bursts if len(b) >= 8]
    print(f"\n== effective throughput, multi-fragment bursts (n={len(big)}) ==")
    for b in big:
        by = sum(x[4] for x in b); dur = (b[-1][0]-b[0][0])/1000.0
        if dur > 0: print(f"   n={len(b):<3} {by:>6}B in {dur:7.1f}ms -> {by/dur*1000/1024:5.1f} KB/s")
    return 0

if __name__ == "__main__":
    sys.exit(main())
