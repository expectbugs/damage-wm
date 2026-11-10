#!/usr/bin/env python3
"""Read a flush journal the way `HANDOFF.md` §31/§32 did, in one command.

    python3 tools/journal_report.py ~/.damage/journal.jsonl
    curl -s 'http://PHONE:7403/journal?token=T&tail=2000000' | python3 tools/journal_report.py -
    (KEEP `&tail=N` until APK 0.61 is installed: an older APK reads the whole file into
    memory without it and its process ends -- HANDOFF.md §71; PHONE is the phone's
    Tailscale address)

Prints: flushes by hour with the median ack per size band (the two radio
regimes show up here), the same by transport (`via`, present since §32), the
shell's own compose/assemble time (since §32), where the link time goes by
size band, the cache's account (§41), TIME TO FIRST VISIBLE CHANGE per gesture
(§42 — the number `WINDOWS.md` §6 judges a window by: bursts of flushes
separated by more than a second, keyed by the first flush's label), and every
`link`/`keeper`/`fault`/`panic` note. Stdlib only; a line that is not JSON is
skipped and counted.

§49 (Phase 0, `FORK.md` M0.5/M0.6): `--since 2026-09-13` (or `2026-09-13T18:00`)
limits every section to records from then on; the glasses' battery changes
(`battery` notes, APK 0.45+) print as a drain summary per discharging stretch;
`probe` notes print in the notes list, and the glasses' own log lines (`glasslog`
notes, from `probe:logger=on`) are counted per arm — `--glasslog` prints them all.
A Damage build's `present` records (`FIRMWARE.md` §3, F1.3: the panel transfer timed on
the glasses while the PRESENTED flag is armed) print as a distribution — split by path once
a contract-2 build reports one (`FIRMWARE.md` §4, mode 24: 0 the full refresh, 1 the hinted
rows), which is F1.7's A/B. A contract-2 build's refusals (`glass` notes carrying `refMode=`)
print as a list, newest last: the ack precedes the decode, so these are the only trace of a
message the glasses threw away. The per-gesture section prints `HANDOFF.md` §57's baseline
(the journal since 08-31) beside the first-flush bytes, the number Phase 2's exit is priced by —
for a journal with no `in` flag and no --since only (a 0.63+ journal lists the no-input bursts
apart, so its rows are not the baseline's and the baseline is not printed).

§73: the atlas's own account, for any journal with `atlas` notes — cache-write messages and bytes,
repacks, how long every face was off around each repack, the flushes that waited behind a cache
write; and from APK 0.63 on the faces that gained glyphs in place and the characters the host
drew for want of a glyph (`unpacked`). Where the
submit records carry `in` (did the pump follow a ring event), a burst no input started is listed
apart as "LABEL (no input)" — the idle chrome tick is not a gesture.

    python3 tools/journal_report.py journal.json --since 2026-09-14 --glasslog
"""
import bisect, collections, datetime, json, re, statistics, sys

BANDS = [(0, 500), (500, 1500), (1500, 3000), (3000, 6000), (6000, 10**9)]
# §42: flushes further apart than this belong to different gestures
BURST_GAP_MS = 1200
# `HANDOFF.md` §57, re-measured for THIS column (2026-09-15, the second review): the first flush
# of a burst, median and p90 bytes, over the same journal (since 08-31). §57's own 396/3,500 and
# 97/660 are a different statistic — every flush's op bytes, not the burst's first — so printing
# them here read as a 660 → 485 B win that was only the two numbers disagreeing.
BASELINE_FIRST_BYTES = {'WINDOW': (401, 3307), 'MAIN': (106, 485)}

def med(v): return int(statistics.median(v)) if v else None

def refusal_report(notes):
    """The image-lane refusals the glasses recorded (contract 2, fields 23-25). The record is
    sticky until the next refusal or a mode-7 sub-0, so a run of identical readings is one
    refusal — but one that comes back AFTER a different reading is a new one, and printing only
    consecutive duplicates once used to drop it (2026-09-15, the second review)."""
    refusals = [n for n in notes if n.get('kind') == 'glass' and 'refMode=' in n.get('detail', '')]
    if not refusals: return
    # the arm belongs in the key (2026-09-16 review): two arms each holding a DIFFERENT sticky
    # record, polled alternately, broke each other's run and printed one refusal per poll
    seen, last = [], {}
    for n in refusals:
        key = re.search(r'refMode=\S+ refReason=\S+ refSeq=\S+', n['detail'])
        if not key: continue
        arm = (re.search(r'\b([LR])\b', n['detail']) or re.search(r'(LEFT|RIGHT)', n['detail']))
        arm = arm.group(0) if arm else '?'
        if key.group(0) != last.get(arm): seen.append((n['t'], arm, key.group(0)))
        last[arm] = key.group(0)
    print('\nimage-lane refusals the glasses recorded (contract 2, fields 23-25; each reading, in order):')
    for t, arm, k in seen:
        print(f'  {datetime.datetime.fromtimestamp(t/1000):%m-%d %H:%M:%S} [{arm}] {k}')

def main(path, since_ms=0, glasslog=False):
    f = sys.stdin if path == '-' else open(path, encoding='utf-8')
    sub, done, notes, presents, bad = {}, [], [], [], 0
    done_in = []                             # the submit's `in` flag for each `done` row, kept beside it
    failed = []          # completed flushes that did NOT succeed — refusals, link-loss completions
    lines_read = 0
    joined = []                              # the submit record each completed flush was joined to
    early_params = []     # link-parameter notes before --since still set the column
    for line in f:
        if not line.strip(): continue
        lines_read += 1
        try: r = json.loads(line)
        except Exception: bad += 1; continue
        if r.get('t', 0) < since_ms:
            if r.get('ev') == 'note' and r.get('kind') == 'link' and 'connection parameters' in r.get('detail', ''):
                early_params = [r]
            continue
        ev = r.get('ev')
        if ev == 'submit': sub[r['id']] = r
        elif ev == 'done' and not r.get('ok'):
            # §36/§42 work is priced on these; dropping them without a word made a day of
            # refusals read as a quiet day (2026-09-16 review)
            failed.append(r)
        elif ev == 'done' and r.get('ok') and 'bytes' in r:
            s = dict(sub.get(r['id'], {}))       # a copy: the completion's own facts ride it below
            s['id'] = r['id']; s['doneT'] = r['t']; s['ackMs'] = r['ackMs']
            joined.append(s)                     # the submit THIS done was joined to, not the last per id
            done_in.append(s.get('in') if isinstance(s.get('in'), int) and s.get('in') >= 0 else None)
            done.append((r['t'], r['bytes'], r['ackMs'], s.get('via', '?'), s.get('handleMs'), s.get('assembleMs'), s.get('label', '?'), s.get('t')))
        elif ev == 'note': notes.append(r)
        elif ev == 'present': presents.append(r)
    if bad: print(f'({bad} unreadable line(s) skipped)')
    if lines_read and bad == lines_read:
        # a bad token or a service that is down reads as a clean report of a quiet day otherwise
        print('every line was unreadable — this is not a journal (a bad token? the service down?)')
        return 2
    if failed:
        print(f'\n{len(failed)} flush(es) completed NOT ok (refusals, link-loss completions) — the first few:')
        for r in failed[:5]:
            print(f'  {datetime.datetime.fromtimestamp(r["t"]/1000):%m-%d %H:%M:%S} id {r.get("id")}: {str(r.get("error"))[:100]}')
    # the file is in ACK-COMPLETION order and three flushes run in flight, so a later, bigger
    # flush can be acked first: sort by the moment each was SUBMITTED, which is what a burst's
    # "first flush" means (2026-09-15, the third review — the report took whichever acked first
    # and priced the gesture by it)
    order = sorted(range(len(done)), key=lambda i: done[i][7] if done[i][7] is not None else done[i][0])
    done = [done[i] for i in order]
    done_in = [done_in[i] for i in order]
    if not done:
        # a window with no flush still has the glasses' own records to show (a self-test run, a
        # quiet shell): the refusals, the transfers and the battery (2026-09-15, second review)
        print('no completed flushes')
        refusal_report(notes)
        present_report(presents)
        battery_report(notes)
        return
    print(f'{len(done)} acked flushes, {datetime.datetime.fromtimestamp(done[0][0]/1000):%Y-%m-%d %H:%M} → {datetime.datetime.fromtimestamp(done[-1][0]/1000):%Y-%m-%d %H:%M}\n')

    # §47: the connection parameters in force, from the shell's `link` notes
    # ("connection parameters: L 15.00ms/1/5000ms … · R …") — the worst arm's
    # interval/latency, as a column per hour: the 105 ms / 4 regime of
    # 2026-09-12 cost every flush ~470 ms and only this column shows it
    # next to the ack medians it explains.
    params = []
    for n in early_params + notes:
        if n.get('kind') != 'link' or 'connection parameters' not in n.get('detail', ''): continue
        pairs = re.findall(r'(\d+(?:\.\d+)?)ms/(\d+)/', n['detail'])
        if pairs: params.append((n['t'], f'{max(float(i) for i, _ in pairs):g}/{max(int(l) for _, l in pairs)}'))
    params.sort()
    def params_at(t):
        cur = None
        for pt, txt in params:
            if pt <= t: cur = txt
            else: break
        return cur or '-'

    def table(groups, title, extra=None):
        print(title)
        # the header names the band it heads (2026-09-16 review: integer division printed
        # (0,500) as "0B-0", (500,1500) as "500B-1K" and (1500,3000) as "1K-3K", while the
        # same bands are printed correctly eleven lines below)
        def band_edge(v):
            if v >= 10**9: return '+'
            if v >= 1000: return f'{v/1000:g}K'
            return f'{v}B'
        print(f'{"":18s} {"n":>6s} ' + ' '.join(f'{band_edge(lo)}-{band_edge(hi)}'.rjust(11) for lo, hi in BANDS)
              + ('  params' if extra else ''))
        for k in sorted(groups):
            v = groups[k]
            cells = []
            for lo, hi in BANDS:
                s = [a for b, a in v if lo <= b < hi]
                cells.append(f'{med(s):5d}({len(s):4d})' if s else '     -     ')
            print(f'{k:18s} {len(v):6d} ' + ' '.join(cells) + (f'  {extra(k)}' if extra else ''))
        print()

    byhour = collections.defaultdict(list); byvia = collections.defaultdict(list); lastInHour = {}
    for t, b, a, via, hm, am, lab, _ts in done:
        h = datetime.datetime.fromtimestamp(t/1000).strftime('%m-%d %H')
        byhour[h].append((b, a))
        lastInHour[h] = max(lastInHour.get(h, 0), t)
        byvia[via].append((b, a))
    table(byhour, 'median ack ms (n) by hour and flush size (params = worst arm interval ms/latency in force at the hour\'s end):',
          extra=lambda h: params_at(lastInHour[h]))
    table(byvia, 'by transport (via):')

    tot = sum(a for _, _, a, *_ in done)
    print('where the link time goes:')
    for lo, hi in BANDS:
        s = [a for _, b, a, *_ in done if lo <= b < hi]
        # every ack 0 ms (an instant transport in a harness) makes the share undefined, not zero
        share = f'{100*sum(s)/tot:4.1f} % of ack time' if tot else 'no ack time recorded'
        if s: print(f'  {lo:>5d}-{hi if hi < 10**9 else "∞":>5}: {len(s):5d} flushes ({100*len(s)/len(done):4.1f} %), {share}')
    hm = [x[4] for x in done if isinstance(x[4], int) and x[4] >= 0]; am = [x[5] for x in done if isinstance(x[5], int) and x[5] >= 0]
    if hm:
        assemble = (f'assemble median {med(am)} ms / p90 {sorted(am)[int(len(am)*.9)]} ms'
                    if am else 'no assembleMs')   # a partly-filled Timing leaves `am` empty
        print(f'\nshell CPU per flush (host, on the loop): handle median {med(hm)} ms / p90 {sorted(hm)[int(len(hm)*.9)]} ms · '
              f'{assemble}  (n={len(hm)})')
        # the §34 split, when the journal has it
        parts = collections.defaultdict(list)
        for r in joined:                          # the same rows `hm`/`am` came from, not one per id
            for k in ('handlerMs', 'mirrorMs', 'slidesMs', 'chromeMs', 'overlaysMs', 'textMs', 'truthMs', 'compressMs', 'compressN'):
                v = r.get(k)
                if isinstance(v, int) and v >= 0: parts[k].append(v)
        if parts:
            print('  split (medians / p90, n=%d): ' % len(joined) + ' · '.join(
                f'{k} {med(v)}/{sorted(v)[int(len(v)*.9)]}' for k, v in parts.items()))
            print('  (handle = handler + slides + overlays + chrome + mirror + ASSEMBLE; assemble = truth + compress + the diff and plan)')
    else:
        print('\nno handleMs/assembleMs in this journal (written before §32)')
    # §41: the cache's account — rects shipped as draws, and why the rest
    # were pixels (the compositor's own reasons, per flush)
    cached = [r.get('cached') for r in joined if isinstance(r.get('cached'), int) and r.get('cached') >= 0]
    if cached:
        miss = collections.Counter()
        for r in joined:
            for part in (r.get('cacheMiss') or '').split(','):
                if '=' in part:
                    k, v = part.split('=', 1)
                    try: miss[k] += int(v)
                    except ValueError: pass
        print(f'\ncached text: {sum(cached)} rect(s) shipped as draws over {len(cached)} flushes; '
              f'pixels by reason: {dict(miss.most_common())}')
    # §66: page staging — rects shipped as one mode-17 draw from a staged record, and the
    # staging traffic itself (the STAGE-labelled writes, off the gesture path)
    staged = [r.get('staged') for r in joined if isinstance(r.get('staged'), int) and r.get('staged') >= 0]
    if staged and sum(staged) > 0:
        stage_flushes = [r for r in joined if r.get('label') == 'STAGE']
        stage_bytes = sum(o.get('bytes', 0) for r in stage_flushes for o in r.get('ops', []))
        print(f'page staging: {sum(staged)} rect(s) shipped as staged draws over {sum(1 for s in staged if s > 0)} flushes; '
              f'{len(stage_flushes)} record write(s), {stage_bytes} B off the gesture path')
    atlas_report(joined, notes)
    # §42: time to first visible change per gesture. A burst = consecutive
    # flushes less than BURST_GAP_MS apart; its first flush's bytes and ack are
    # what the eye waits for. The silent clock's minute tick and atlas chunks
    # are not gestures.
    bursts = collections.defaultdict(list)
    prev_t = None; cur = None
    unjoined = 0
    # §73: whether a flush's pump followed a ring event (`in`, written since APK 0.63). Where the
    # journal says, a burst that no input started — the 5 s idle chrome tick, a provider's push —
    # is listed apart: on 2026-11-09 they were 91 % of the "WINDOW" row and made it read 58 B / 64 ms.
    # The flag rides each ROW (the submit this completion was joined to): flush ids start over
    # with every process, so a table keyed by id read one session's flag for another's flush.
    prev_in = None                           # the `in` flag of the flush before this one
    split_by_in = any(i is not None for i in done_in)
    for (t, b, a, via, hm, am, lab, ts), started in zip(done, done_in):
        # not gestures — and they must not extend one either: leaving `prev_t` on an atlas chunk
        # glued the next real gesture onto the burst before it and lost that gesture's own first
        # flush, the number `WINDOWS.md` §6 judges a window by (2026-09-15, the third review)
        if lab in ('SILENT', 'ATLAS', 'STAGE'): continue   # §66: staged-record writes are not gestures either
        if ts is None:
            # no submit record for this flush — the usual cause is `?tail=N`, which cuts the file
            # at a byte offset. Falling back to the ACK time put the flush in whatever burst was
            # running then and made a whole gesture disappear (2026-09-16 review); it is counted
            # and left out instead.
            unjoined += 1
            continue
        # a ring event's flush that follows a flush no input started — an idle tick, an animation
        # frame, a push — is a gesture of its own, whoever opened the burst: glued on, its first
        # flush (the number the gesture is priced by) was counted as the burst's second or third
        if cur is None or prev_t is None or ts - prev_t > BURST_GAP_MS or (started == 1 and prev_in == 0):
            if cur: bursts[cur[0]].append(cur)
            # a journal that holds rows from before the flag existed (an APK before 0.63) and after:
            # the old rows cannot be split, and under the plain label they would put the idle
            # ticks back into the gesture row — listed apart, named for what they are
            name = lab if started == 1 else f'{lab} (no input)' if started == 0 else f'{lab} (flag not journaled)' if split_by_in else lab
            cur = [name, b, a, 0, 0, t - ts]
        cur[3] += 1; cur[4] += b
        prev_t = ts
        prev_in = started
    if cur: bursts[cur[0]].append(cur)
    if unjoined:
        print(f'\n({unjoined} completed flush(es) had no submit record — the file starts mid-stream; '
              'they are left out of the gesture table. A larger &tail= gives a fuller one; fetch the '
              'whole journal only from APK 0.61 on -- HANDOFF.md §71.)')
    if bursts:
        def p90(v): v = sorted(v); return v[min(len(v) - 1, int(len(v) * 0.9))]
        print('\ntime to first visible change per gesture (bursts by first-flush label; median / p90):')
        print('  "wait" is submit -> done, what the gesture actually waited; "ack" is the link\'s own')
        print('  share of it (the lane\'s clock starts when it dequeues, so a backed-up link hides there).')
        print(f'  {"label":28s} {"bursts":>6s}  {"first bytes":>14s}  {"first wait ms":>14s}  {"first ack ms":>14s}  {"flushes":>7s}  {"burst bytes":>11s}')
        for lab, v in sorted(bursts.items(), key=lambda kv: -len(kv[1])):
            fb = [x[1] for x in v]; fa = [x[2] for x in v]; n = [x[3] for x in v]; tb = [x[4] for x in v]
            fw = [x[5] for x in v]
            base = BASELINE_FIRST_BYTES.get(str(lab)) if since_ms == 0 and not split_by_in else None
            print(f'  {str(lab):28s} {len(v):6d}  {med(fb):6d} / {p90(fb):5d}  {med(fw):6d} / {p90(fw):5d}  {med(fa):6d} / {p90(fa):5d}  {med(n):7d}  {med(tb):11d}'
                  + (f'   (§57 baseline {base[0]} / {base[1]} B)' if base else ''))
        if since_ms:
            print('  (the §57 byte baseline is over the whole journal since 08-31; it is not printed '
                  'beside a --since window, where it would compare two different sets)')
        elif split_by_in:
            print('  (the §57 byte baseline counted every burst under its label, the idle ticks with the '
                  'gestures; these rows list the bursts no input started apart, so it is not printed beside them)')
    refusal_report(notes)
    present_report(presents)
    battery_report(notes)
    kinds = collections.Counter(n['kind'] for n in notes)
    print(f'\nnotes: {dict(kinds)}')
    for n in notes:
        if n['kind'] in ('link', 'panic', 'halt', 'build', 'watchdog', 'restart', 'keeper', 'probe') or (n['kind'] == 'fault' and 'stall' in n['detail']):
            print(f'  {datetime.datetime.fromtimestamp(n["t"]/1000):%m-%d %H:%M:%S} {n["kind"]}: {n["detail"][:110]}')
    # the other faults were counted in the line above and shown nowhere — the lost-ack class (§34)
    # was visible only as a digit (2026-09-16 review). One line per class, with the first of each.
    others = collections.defaultdict(list)
    for n in notes:
        if n['kind'] == 'fault' and 'stall' not in n['detail']:
            others[n['detail'].split(':')[0][:40]].append(n)
    if others:
        print('  other faults, by class:')
        for k, v in sorted(others.items(), key=lambda kv: -len(kv[1])):
            print(f'    {len(v):5d}x  {k}  (first {datetime.datetime.fromtimestamp(v[0]["t"]/1000):%m-%d %H:%M:%S}: {v[0]["detail"][:80]})')
    # HANDOFF.md §54: the atlas at each session start — kept across the rebuild (no upload)
    # or reset, with the transport's per-arm lease gaps; the saving is these lines
    starts = [n for n in notes if n['kind'] == 'atlas' and (n['detail'].startswith('kept across') or n['detail'].startswith('reset'))]
    if starts:
        kept = sum(1 for n in starts if n['detail'].startswith('kept across'))
        print(f'\natlas at session start: {len(starts)} session(s), {kept} kept across a rebuild, {len(starts) - kept} reset')
        for n in starts:
            print(f'  {datetime.datetime.fromtimestamp(n["t"]/1000):%m-%d %H:%M:%S} {n["detail"][:150]}')
    glass = [n for n in notes if n['kind'] == 'glasslog']
    if glass:
        per_arm = collections.Counter(n['detail'][:1] for n in glass)
        print(f'\nglasses\' own log lines (probe:logger): {len(glass)} — per arm {dict(per_arm)}'
              + ('' if glasslog else ' (--glasslog prints them)'))
        if glasslog:
            for n in glass:
                print(f'  {datetime.datetime.fromtimestamp(n["t"]/1000):%m-%d %H:%M:%S.%f}'[:-3] + f' {n["detail"]}')

def atlas_report(joined, notes):
    """§73: the texture atlas's own traffic and what it cost the text — chunks and bytes, repacks,
    faces that gained glyphs, how long every face was off around each repack, the characters the
    host drew for want of a glyph, and the flushes that waited behind a cache write."""
    atlas = [r for r in joined if r.get('label') == 'ATLAS']
    an = [n for n in notes if n['kind'] == 'atlas']
    if not atlas and not an: return
    def ts(n): return f'{datetime.datetime.fromtimestamp(n["t"]/1000):%m-%d %H:%M:%S}'
    nbytes = sum(o.get('bytes', 0) for r in atlas for o in r.get('ops', []))
    ack = sum(r.get('ackMs') or 0 for r in atlas)
    repacks = [n for n in an if n['detail'].startswith('repacking')]
    gained = [n for n in an if 'gained glyphs' in n['detail']]
    faces = 0
    for n in gained:
        m = re.match(r'(\d+) face\(s\) gained glyphs', n['detail'])
        faces += int(m.group(1)) if m else 1
    refused = [n for n in an if 'cannot gain' in n['detail']]
    full = [n for n in an if n['detail'].startswith('full')]
    print(f'\natlas traffic: {len(atlas)} message(s), {nbytes} B, {ack / 1000:.1f} s of acks; '
          f'{len(repacks)} repack(s), {faces} face extension(s) in {len(gained)} promotion(s), '
          f'{len(refused)} refused, {len(full)} "full" episode(s)')
    # every face off: from a repack to the next "uploaded". A repack whose upload never finished in
    # this session (the session ended, the lease lapsed, cached text went off, another writer took
    # the cache) is not a span: its "uploaded" would be a later session's, hours on.
    spans = []
    start = None
    ENDS = ('reset', 'kept across', 'cached text off', 'the lease lapsed', 'the texture cache was written by')
    for n in an:
        d = n['detail']
        if d.startswith('repacking') and start is None: start = n
        elif d.startswith('uploaded') and start is not None:
            spans.append((start, n)); start = None
        elif d.startswith(ENDS): start = None
    for a, b in spans:
        inside = [r for r in joined if r.get('t') is not None and a['t'] <= r['t'] <= b['t'] and r.get('label') not in ('ATLAS', 'STAGE')]
        pix = sum(o.get('bytes', 0) for r in inside for o in r.get('ops', []))
        print(f'  {ts(a)} every face off for {(b["t"] - a["t"]) / 1000:.1f} s (a repack): '
              f'{len(inside)} other flush(es), {pix} B, went out meanwhile')
    unpacked = [r.get('unpacked') for r in joined if isinstance(r.get('unpacked'), int) and r.get('unpacked') >= 0]
    if unpacked:
        hit = [u for u in unpacked if u > 0]
        # a cacheable code a live face's table does not hold: not packed yet, waiting on a promotion,
        # or one the face has no glyph for at all (that last kind never reaches 0)
        print(f'  characters the host drew for want of a glyph: {sum(unpacked)} over {len(hit)} of {len(unpacked)} flushes')
    # head-of-line: a flush submitted while a cache write was on the lane waits out its remainder
    writes = sorted((r['t'], r['doneT']) for r in joined if r.get('label') in ('ATLAS', 'STAGE') and r.get('t') is not None)
    if writes:
        # one binary search per flush: the latest write submitted at or before it, and the furthest
        # any write up to that one ran (a running maximum, so overlapping writes are still right)
        starts = [w0 for w0, _ in writes]
        reach = []
        for _, w1 in writes: reach.append(max(w1, reach[-1]) if reach else w1)
        behind = []
        for r in joined:
            if r.get('label') in ('ATLAS', 'STAGE', 'SILENT') or r.get('t') is None: continue
            i = bisect.bisect_right(starts, r['t']) - 1
            if i >= 0 and reach[i] > r['t']: behind.append(r['doneT'] - r['t'])
        if behind:
            behind.sort()
            print(f'  flushes submitted behind a cache write: {len(behind)} — wait median {behind[len(behind)//2]} ms, '
                  f'p90 {behind[min(len(behind)-1, int(len(behind)*.9))]} ms')
    for n in refused[:5]: print(f'  {ts(n)} {n["detail"][:160]}')

def present_report(presents):
    """`FIRMWARE.md` §3 (F1.3, a Damage build with PRESENTED armed): each `present` record is one
    panel transfer of a Damage frame, timed on the glasses — worker, copy and transfer µs. The
    transfer is the part no ack ever showed; its distribution bounds the motion tick (`FORK.md` §5)."""
    if not presents: return
    def p90(v): v = sorted(v); return v[min(len(v) - 1, int(len(v) * 0.9))]
    def col(k):
        v = [int(r[k]) for r in presents if isinstance(r.get(k), int) and r[k] >= 0]
        return f'{med(v):6d} / {p90(v):6d}' if v else '     -'
    print(f'\npanel transfers timed on the glasses ({len(presents)} presents, median / p90 µs): '
          f'worker {col("workerUs")} · copy {col("copyUs")} · transfer {col("transferUs")}')
    # F1.7's A/B (`FIRMWARE.md` §4, mode 24): the transfer by path, once a contract-2 build reports one
    bypath = collections.defaultdict(list)
    for r in presents:
        if isinstance(r.get('transferUs'), int) and isinstance(r.get('path'), int): bypath[r['path']].append(int(r['transferUs']))
    if bypath:
        for pth in sorted(bypath):
            v = bypath[pth]; print(f'  path {pth} ({"the full refresh" if pth == 0 else "the hinted rows"}): n={len(v):5d}  transfer {med(v):6d} / {p90(v):6d} µs')
    byhour = collections.defaultdict(list)
    for r in presents:
        if isinstance(r.get('transferUs'), int): byhour[datetime.datetime.fromtimestamp(r['t']/1000).strftime('%m-%d %H')].append(int(r['transferUs']))
    if len(byhour) > 1:
        for h in sorted(byhour):
            v = byhour[h]; print(f'  {h}  n={len(v):5d}  transfer {med(v):6d} / {p90(v):6d} µs')

def battery_report(notes):
    """§49 (M0.6): the glasses' battery from `battery` notes ("glasses 61% charging").
    A stretch is a run of readings with no charging; its drain is the level lost
    over its hours. Stretches under 30 minutes or 2 points are not rated."""
    pts = []
    for n in notes:
        if n.get('kind') != 'battery': continue
        m = re.match(r'glasses (\d+)%( charging)?', n.get('detail', ''))
        if m: pts.append((n['t'], int(m.group(1)), bool(m.group(2))))
    if not pts: return
    pts.sort()
    print(f'\nglasses battery ({len(pts)} changes, {pts[0][1]}% → {pts[-1][1]}%): discharging stretches')
    # a stretch also ends at a gap in the readings: broken only by a `charging` reading, two
    # readings, three days of silence and two more printed as one 73 h stretch (2026-09-16 review)
    print(f'  {"from":14s} {"to":14s} {"hours":>6s} {"level":>11s} {"%/h":>6s}')
    stretch = []
    def flush(st):
        if len(st) < 2: return
        h = (st[-1][0] - st[0][0]) / 3.6e6
        lost = st[0][1] - st[-1][1]
        rate = f'{lost / h:6.1f}' if h >= 0.5 and lost >= 2 else '     -'
        print(f'  {datetime.datetime.fromtimestamp(st[0][0]/1000):%m-%d %H:%M}    {datetime.datetime.fromtimestamp(st[-1][0]/1000):%m-%d %H:%M}    {h:6.1f} {st[0][1]:4d}→{st[-1][1]:3d} % {rate}')
    GAP_MS = 2 * 3600 * 1000       # no reading for two hours: the glasses were away, not draining
    for t, lvl, chg in pts:
        if chg or (stretch and t - stretch[-1][0] > GAP_MS):
            flush(stretch); stretch = []
        if not chg:
            stretch.append((t, lvl))
    flush(stretch)

USAGE = '''usage: journal_report.py [PATH|-] [--since YYYY-MM-DD[THH:MM]] [--glasslog]

Reads the phone's journal (`curl '.../journal?token=...&tail=2000000' | journal_report.py -`; drop
`&tail=` only from APK 0.61 on, HANDOFF.md §71) and prints the
per-gesture, per-band and per-glass numbers `WINDOWS.md` §6 and `REMINDER.md` price with.
Exit status: 0 a report (an empty one included), 2 the input was not a journal.'''

if __name__ == '__main__':
    args = sys.argv[1:]
    if '--help' in args or '-h' in args:
        print(USAGE); raise SystemExit(0)
    since = 0
    if '--since' in args:
        i = args.index('--since')
        since = int(datetime.datetime.fromisoformat(args[i + 1]).timestamp() * 1000)
        del args[i:i + 2]
    show_glass = '--glasslog' in args
    if show_glass: args.remove('--glasslog')
    # an unknown flag used to be taken as the path, and a second path silently dropped
    # (2026-09-16 review): say so rather than report on the wrong input
    bad_args = [a for a in args if a.startswith('-') and a != '-']
    if bad_args or len(args) > 1:
        print(USAGE); raise SystemExit(f'\nunrecognised argument(s): {bad_args or args[1:]}')
    raise SystemExit(main(args[0] if args else '-', since, show_glass) or 0)
