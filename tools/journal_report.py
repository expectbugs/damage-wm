#!/usr/bin/env python3
"""Read a flush journal the way `HANDOFF.md` §31/§32 did, in one command.

    python3 tools/journal_report.py ~/.damage/journal.jsonl
    curl -s 'http://aphone:7403/journal?token=T' | python3 tools/journal_report.py -

Prints: flushes by hour with the median ack per size band (the two radio
regimes show up here), the same by transport (`via`, present since §32), the
shell's own compose/assemble time (since §32), where the link time goes by
size band, the cache's account (§41), TIME TO FIRST VISIBLE CHANGE per gesture
(§42 — the number `WINDOWS.md` §6 judges a window by: bursts of flushes
separated by more than a second, keyed by the first flush's label), and every
`link`/`keeper`/`fault`/`panic` note. Stdlib only; a line that is not JSON is
skipped and counted.
"""
import collections, datetime, json, statistics, sys

BANDS = [(0, 500), (500, 1500), (1500, 3000), (3000, 6000), (6000, 10**9)]
# §42: flushes further apart than this belong to different gestures
BURST_GAP_MS = 1200

def med(v): return int(statistics.median(v)) if v else None

def main(path):
    f = sys.stdin if path == '-' else open(path, encoding='utf-8')
    sub, done, notes, bad = {}, [], [], 0
    for line in f:
        try: r = json.loads(line)
        except Exception: bad += 1; continue
        ev = r.get('ev')
        if ev == 'submit': sub[r['id']] = r
        elif ev == 'done' and r.get('ok') and 'bytes' in r:
            s = sub.get(r['id'], {})
            done.append((r['t'], r['bytes'], r['ackMs'], s.get('via', '?'), s.get('handleMs'), s.get('assembleMs'), s.get('label', '?'), s.get('t')))
        elif ev == 'note': notes.append(r)
    if bad: print(f'({bad} unreadable line(s) skipped)')
    if not done: print('no completed flushes'); return
    print(f'{len(done)} acked flushes, {datetime.datetime.fromtimestamp(done[0][0]/1000):%Y-%m-%d %H:%M} → {datetime.datetime.fromtimestamp(done[-1][0]/1000):%Y-%m-%d %H:%M}\n')

    def table(groups, title):
        print(title)
        print(f'{"":18s} {"n":>6s} ' + ' '.join(f'{lo//1000 if lo>=1000 else lo}{"K" if lo>=1000 else "B"}-{(hi//1000 if hi<10**9 else "")}{"K" if 1000<=hi<10**9 else ""}'.rjust(11) for lo, hi in BANDS))
        for k in sorted(groups):
            v = groups[k]
            cells = []
            for lo, hi in BANDS:
                s = [a for b, a in v if lo <= b < hi]
                cells.append(f'{med(s):5d}({len(s):4d})' if s else '     -     ')
            print(f'{k:18s} {len(v):6d} ' + ' '.join(cells))
        print()

    byhour = collections.defaultdict(list); byvia = collections.defaultdict(list)
    for t, b, a, via, hm, am, lab, _ts in done:
        byhour[datetime.datetime.fromtimestamp(t/1000).strftime('%m-%d %H')].append((b, a))
        byvia[via].append((b, a))
    table(byhour, 'median ack ms (n) by hour and flush size:')
    table(byvia, 'by transport (via):')

    tot = sum(a for _, _, a, *_ in done)
    print('where the link time goes:')
    for lo, hi in BANDS:
        s = [a for _, b, a, *_ in done if lo <= b < hi]
        if s: print(f'  {lo:>5d}-{hi if hi < 10**9 else "∞":>5}: {len(s):5d} flushes ({100*len(s)/len(done):4.1f} %), {100*sum(s)/tot:4.1f} % of ack time')
    hm = [x[4] for x in done if isinstance(x[4], int) and x[4] >= 0]; am = [x[5] for x in done if isinstance(x[5], int) and x[5] >= 0]
    if hm:
        print(f'\nshell CPU per flush (host, on the loop): handle median {med(hm)} ms / p90 {sorted(hm)[int(len(hm)*.9)]} ms · '
              f'assemble median {med(am)} ms / p90 {sorted(am)[int(len(am)*.9)]} ms  (n={len(hm)})')
        # the §34 split, when the journal has it
        parts = collections.defaultdict(list)
        for r in sub.values():
            for k in ('handlerMs', 'mirrorMs', 'slidesMs', 'chromeMs', 'overlaysMs', 'textMs', 'truthMs', 'compressMs', 'compressN'):
                v = r.get(k)
                if isinstance(v, int) and v >= 0: parts[k].append(v)
        if parts:
            print('  split (medians / p90): ' + ' · '.join(
                f'{k} {med(v)}/{sorted(v)[int(len(v)*.9)]}' for k, v in parts.items()))
            print('  (handle = handler + slides + overlays + chrome + mirror + ASSEMBLE; assemble = truth + compress + the diff and plan)')
    else:
        print('\nno handleMs/assembleMs in this journal (written before §32)')
    # §41: the cache's account — rects shipped as draws, and why the rest
    # were pixels (the compositor's own reasons, per flush)
    cached = [r.get('cached') for r in sub.values() if isinstance(r.get('cached'), int) and r.get('cached') >= 0]
    if cached:
        miss = collections.Counter()
        for r in sub.values():
            for part in (r.get('cacheMiss') or '').split(','):
                if '=' in part:
                    k, v = part.split('=', 1)
                    try: miss[k] += int(v)
                    except ValueError: pass
        print(f'\ncached text: {sum(cached)} rect(s) shipped as draws over {len(cached)} flushes; '
              f'pixels by reason: {dict(miss.most_common())}')
    # §42: time to first visible change per gesture. A burst = consecutive
    # flushes less than BURST_GAP_MS apart; its first flush's bytes and ack are
    # what the eye waits for. The silent clock's minute tick and atlas chunks
    # are not gestures.
    bursts = collections.defaultdict(list)
    prev_t = None; cur = None
    for t, b, a, via, hm, am, lab, ts in done:
        t = ts if ts is not None else t          # the SUBMIT is the gesture's moment; the ack is what it waited for
        if lab in ('SILENT', 'ATLAS'): prev_t = t; continue
        if cur is None or prev_t is None or t - prev_t > BURST_GAP_MS:
            if cur: bursts[cur[0]].append(cur)
            cur = [lab, b, a, 0, 0]
        cur[3] += 1; cur[4] += b
        prev_t = t
    if cur: bursts[cur[0]].append(cur)
    if bursts:
        def p90(v): v = sorted(v); return v[min(len(v) - 1, int(len(v) * 0.9))]
        print('\ntime to first visible change per gesture (bursts by first-flush label; median / p90):')
        print(f'  {"label":22s} {"bursts":>6s}  {"first bytes":>14s}  {"first ack ms":>14s}  {"flushes":>7s}  {"burst bytes":>11s}')
        for lab, v in sorted(bursts.items(), key=lambda kv: -len(kv[1])):
            fb = [x[1] for x in v]; fa = [x[2] for x in v]; n = [x[3] for x in v]; tb = [x[4] for x in v]
            print(f'  {str(lab):22s} {len(v):6d}  {med(fb):6d} / {p90(fb):5d}  {med(fa):6d} / {p90(fa):5d}  {med(n):7d}  {med(tb):11d}')
    kinds = collections.Counter(n['kind'] for n in notes)
    print(f'\nnotes: {dict(kinds)}')
    for n in notes:
        if n['kind'] in ('link', 'panic', 'halt', 'build', 'watchdog', 'restart', 'keeper') or (n['kind'] == 'fault' and 'stall' in n['detail']):
            print(f'  {datetime.datetime.fromtimestamp(n["t"]/1000):%m-%d %H:%M:%S} {n["kind"]}: {n["detail"][:110]}')

if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else '-')
