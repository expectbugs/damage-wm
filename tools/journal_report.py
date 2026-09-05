#!/usr/bin/env python3
"""Read a flush journal the way `HANDOFF.md` §31/§32 did, in one command.

    python3 tools/journal_report.py ~/.damage/journal.jsonl
    curl -s 'http://aphone:7403/journal?token=T' | python3 tools/journal_report.py -

Prints: flushes by hour with the median ack per size band (the two radio
regimes show up here), the same by transport (`via`, present since §32), the
shell's own compose/assemble time (since §32), where the link time goes by
size band, and every `link`/`fault`/`panic` note. Stdlib only; a line that is
not JSON is skipped and counted.
"""
import collections, datetime, json, statistics, sys

BANDS = [(0, 500), (500, 1500), (1500, 3000), (3000, 6000), (6000, 10**9)]

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
            done.append((r['t'], r['bytes'], r['ackMs'], s.get('via', '?'), s.get('handleMs'), s.get('assembleMs'), s.get('label', '?')))
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
    for t, b, a, via, hm, am, lab in done:
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
    else:
        print('\nno handleMs/assembleMs in this journal (written before §32)')
    kinds = collections.Counter(n['kind'] for n in notes)
    print(f'\nnotes: {dict(kinds)}')
    for n in notes:
        if n['kind'] in ('link', 'panic', 'halt') or (n['kind'] == 'fault' and 'stall' in n['detail']):
            print(f'  {datetime.datetime.fromtimestamp(n["t"]/1000):%m-%d %H:%M:%S} {n["kind"]}: {n["detail"][:110]}')

if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else '-')
