#!/usr/bin/env python3
"""Packets per connection event, per arm, from a BTSnoop capture (`FORK.md` M0.3, the
per-event read `HANDOFF.md` §50.8 owed).

Context: Damage's firmware fork (`FORK.md`) prices its link levers (F1.8: bigger ATT writes,
packing) on how many link-layer packets the phone's radio gets across per connection event
today. The controller's Number-of-Completed-Packets events (HCI event 0x13) say when each
outbound ACL packet was acknowledged by the peer, and the outbound ACL records say when the
host queued it — so the capture answers, per arm: how many packets complete per report, how
far apart the reports are (the event cadence the peer honours), and how many were queued at
the time (whether the host or the link paces a flush). Offline, read-only, stdlib only.

    python3 research/perevent.py                      # every captures/apk-*.log
    python3 research/perevent.py captures/apk-20260914-1731-1756.log

Arms are told apart by traffic: the handle that receives the bulk of the outbound ACL bytes
is LEFT (images), the other RIGHT (control) — `CLAUDE.md`'s arm split. Timestamps are the
capture's own (microseconds); only differences are used.
"""
import collections, glob, os, statistics, struct, sys

def records(path):
    d = open(path, 'rb').read()
    if d[:8] != b'btsnoop\x00':
        return
    off = 16
    while off + 24 <= len(d):
        ol, il, flags, drops, ts = struct.unpack('>IIIIq', d[off:off + 24]); off += 24
        pkt = d[off:off + il]; off += il
        yield ts, flags, pkt

def pct(xs, p):
    if not xs: return 0
    xs = sorted(xs); return xs[min(len(xs) - 1, int(p * len(xs)))]

def analyse(path, interval_ms_hint=15.0):
    sent = collections.defaultdict(list)          # handle -> [ts of outbound ACL first-fragment]
    sent_bytes = collections.Counter()
    nocp = collections.defaultdict(list)          # handle -> [(ts, n)]
    intervals = {}                                # handle -> last connection interval ms
    peers = {}
    for ts, flags, pkt in records(path):
        if not pkt: continue
        if pkt[0] == 0x02 and (flags & 1) == 0 and len(pkt) >= 5:        # ACL, host -> controller
            h = struct.unpack('<H', pkt[1:3])[0] & 0x0FFF
            sent[h].append(ts); sent_bytes[h] += len(pkt) - 5
        elif pkt[0] == 0x04 and len(pkt) >= 3:
            code, body = pkt[1], pkt[3:]
            if code == 0x13 and body:                                       # Number Of Completed Packets
                n = body[0]
                for i in range(n):
                    if 1 + 4 * i + 4 > len(body): break
                    h, c = struct.unpack('<HH', body[1 + 4 * i:5 + 4 * i])
                    nocp[h].append((ts, c))
            elif code == 0x3E and len(body) >= 2:
                sub = body[0]; b = body[1:]
                if sub == 0x01 and len(b) >= 18:
                    h = struct.unpack('<H', b[1:3])[0]; peers[h] = ':'.join('%02X' % x for x in reversed(b[5:11]))
                    intervals[h] = struct.unpack('<H', b[11:13])[0] * 1.25
                elif sub == 0x0A and len(b) >= 30:
                    h = struct.unpack('<H', b[1:3])[0]; peers[h] = ':'.join('%02X' % x for x in reversed(b[5:11]))
                    intervals[h] = struct.unpack('<H', b[23:25])[0] * 1.25
                elif sub == 0x03 and len(b) >= 9:
                    h = struct.unpack('<H', b[1:3])[0]; intervals[h] = struct.unpack('<H', b[3:5])[0] * 1.25
    print(os.path.basename(path))
    # the LE links are the ones whose connection the capture saw; the bulk one is LEFT (images),
    # the other RIGHT (control); a handle with no LE connection event is a classic link (the earbud)
    le = sorted((h for h in sent if h in peers), key=lambda h: -sent_bytes[h])
    handles = le + sorted((h for h in sent if h not in peers), key=lambda h: -sent_bytes[h])
    label = {}
    if le: label[le[0]] = 'LEFT (bulk)'
    if len(le) > 1: label[le[1]] = 'RIGHT (control)'
    for h in handles:
        arm = label.get(h, 'classic (no LE connection event)' if h not in peers else 'LE (a retried connect)')
        reports = nocp.get(h, [])
        print(f'  handle {h} peer {peers.get(h, "?")} — {arm}: {len(sent[h])} ACL packets out, {sent_bytes[h]} B; '
              f'{len(reports)} completed-packet reports; last interval {intervals.get(h, 0):.2f} ms')
        if len(reports) < 20: continue
        # queue depth just before each report: packets handed to the controller minus packets completed
        # before it — whether the host had more waiting than the link took
        s_ts = sorted(sent[h]); si = 0; done = 0
        per_report = collections.Counter(); per_report_busy = collections.Counter()
        busy_reports = 0
        for ts, c in reports:
            while si < len(s_ts) and s_ts[si] <= ts: si += 1
            depth = si - done                       # queued at the time of this report, this one's packets included
            per_report[c] += 1
            if depth > c:                           # more waited than completed: the link, not the host, paced it
                per_report_busy[c] += 1; busy_reports += 1
            done += c
        gaps = [(b[0] - a[0]) / 1000.0 for a, b in zip(reports, reports[1:]) if b[0] - a[0] < 200_000]
        iv = intervals.get(h) or interval_ms_hint
        gap_bins = collections.Counter(round(g / iv) for g in gaps)
        print(f'    completed per report: ' + ', '.join(f'{k}×{v}' for k, v in sorted(per_report.items())))
        print(f'    …when more were queued than completed ({busy_reports} reports): '
              + (', '.join(f'{k}×{v}' for k, v in sorted(per_report_busy.items())) or '—'))
        print(f'    gap between reports: median {statistics.median(gaps):.1f} ms, p10 {pct(gaps, .1):.1f}, p90 {pct(gaps, .9):.1f}; '
              f'in units of the {iv:.2f} ms interval: ' + ', '.join(f'{k}×{v}' for k, v in sorted(gap_bins.items())[:8]))
        # host pacing: the time between a packet being handed over and its completion, for busy stretches
        if busy_reports:
            # per busy report, packets completed per interval of link time since the previous report
            rates = []
            prev = None
            for ts, c in reports:
                if prev is not None and 0 < ts - prev < 200_000: rates.append(c * 1000.0 / ((ts - prev) / 1000.0) * 247 / 1000)
                prev = ts
            print(f'    link throughput between consecutive reports (247 B packets): median {statistics.median(rates):.1f} KB/s, p90 {pct(rates, .9):.1f} KB/s')
        # the busiest ten seconds: bytes completed
        if reports:
            t0 = reports[0][0]; buckets = collections.Counter()
            for ts, c in reports: buckets[(ts - t0) // 10_000_000] += c
            top = max(buckets.values())
            print(f'    busiest 10 s: {top} packets completed ({top * 247 / 10 / 1000:.1f} KB/s at 247 B)')
        # the cadence over time, for the bulk arm: per 30 s, the median gap between busy reports
        # beside what the other links carried in the same 30 s — whether the cadence follows them
        if arm.startswith('LEFT'):
            t0 = min(min(v) for v in sent.values())
            others = {o: collections.Counter() for o in sent if o != h}
            for o in others:
                for ts in sent[o]: others[o][(ts - t0) // 30_000_000] += 1
            rows = collections.defaultdict(list); prev = None; si = 0; done = 0
            for ts, c in reports:
                while si < len(s_ts) and s_ts[si] <= ts: si += 1
                busy = (si - done) > c; done += c
                if prev is not None and busy and 0 < ts - prev < 200_000: rows[(ts - t0) // 30_000_000].append((ts - prev) / 1000.0)
                prev = ts
            print('    cadence of busy reports per 30 s (median gap ms / reports) beside the other links\' packets in that 30 s:')
            for k in sorted(rows):
                if len(rows[k]) < 10: continue
                oth = ' '.join(f'h{o}={others[o][k]}' for o in sorted(others))
                print(f'      t+{k * 30:4d}s: {statistics.median(rows[k]):5.1f} ms / {len(rows[k]):4d}   {oth}')

if __name__ == '__main__':
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'captures')
    files = sys.argv[1:] or sorted(glob.glob(os.path.join(root, 'apk-*.log')))
    for f in files:
        analyse(f)
