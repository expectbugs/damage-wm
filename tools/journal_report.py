#!/usr/bin/env python3
"""Read a flush journal the way `HANDOFF.md` §31/§32 did, in one command.

    python3 tools/journal_report.py ~/.damage/journal.jsonl
    curl -s 'http://PHONE:7403/journal?token=T&tail=2000000' | python3 tools/journal_report.py -
    (KEEP `&tail=N` until APK 0.61 is installed: an older APK reads the whole file into
    memory without it and its process ends -- HANDOFF.md §71; PHONE is the phone's
    Tailscale address)

From APK 0.64 the journal ROTATES at a process start once it is past 64 MiB (HANDOFF.md §75.1):
the file is set aside as `journal-YYYYMMDD-HHMMSS.jsonl`, nothing is deleted, and the fresh live
file's first line is a `journal` note naming the archive that holds what came before it. A tail
never spans the two; the earlier lines are one fetch by name away:

    curl -s 'http://PHONE:7403/journals?token=T'                 # the live file and its archives, with sizes
    curl -s 'http://PHONE:7403/journal?token=T&name=journal-20261110-213045.jsonl&tail=2000000' | python3 tools/journal_report.py -
    python3 tools/journal_pull.py --live                         # the archives and the live file → ~/.damage/phone-journal/
    python3 tools/journal_report.py ~/.damage/phone-journal/journal-*.jsonl ~/.damage/phone-journal/journal.jsonl
    (a `journal-live-<time>.jsonl` there is a live copy the pull kept because no archive it copied holds its
    lines — the app installed again, or that archive not copied; the glob reads it too)

Several inputs are read as ONE journal, in the order of their first record's time whatever the
order on the command line (`-`, at most once, is read last: it is taken as the newest). The report
then opens with a header of 1 + N lines, one per input with its first and last record time, and
one more line for each input that starts before the one ahead of it ends (their lines interleave; a
flush both files hold is counted twice; the report still prints). Among several inputs each must be a
regular file — each is looked into for its first record before it is read, which a pipe would not
survive; a pipe, FIFO or `<(…)` is read alone or as `-` (R2).

Prints: flushes by hour with the median ack per size band (the two radio
regimes show up here), the same by transport (`via`, present since §32), the
shell's own compose/assemble time (since §32), where the link time goes by
size band, the cache's account (§41), TIME TO FIRST VISIBLE CHANGE per gesture
(§42 — the number `WINDOWS.md` §6 judges a window by: bursts of flushes
separated by more than a second, keyed by the first flush's label), and every
`link`/`keeper`/`fault`/`panic` note, the `journal` notes (a rotation), the `process` notes (the
uncaught-error record in place; why Android says an earlier process ended) and every `uncaught`
note WHOLE (the last run's uncaught-error record: the journal is the only copy that can be read
off the phone). Stdlib only; a line that is not JSON is skipped and counted.

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

0.64 (HANDOFF.md §75.4): the shell writes a `power` note at every change of its power state — `lit`
(painting), `shell-silent` (its own silent clock screen), `rest`, `fw-silent` (asleep for the firmware's
Silent Mode) and `down` (no session), each live state with the glasses' level when one has been read.
From those and the `battery` notes the report prints the glasses' battery %/h BY STATE: every 1 % step
is charged to the state in force when it was read, and beside it each interval's own level at its start
against its end (the step method carries up to 1 % at each boundary of a state). A timed hour of
`probe:rest=on` is read from the `rest` row. The poll's telemetry read (one a minute while painting,
0.64) also prints as a CANDIDATE block: spans where the glasses' `presents` counter advanced while
`transferUs` read the same at both ends. The notes list gains `wear`, `case`, `sid`, `rest`, `lowbatt`.

0.64 R1: the `cache count:` line counts the count's own readings — the `stage` notes APK 0.63 wrote from
the atlas read-back (subject "the atlas check") are left out — and names a shortfall of staged strips
alone; the `cached text:` line says what its reasons count since 0.64 and prints no zero entry. Read a
journal that spans the upgrade to 0.64 with `--since` (the two builds count in different units).
`--selftest` runs the report's own checks over the synthetic journals in tools/fixtures/journal_report/.

    python3 tools/journal_report.py journal.json --since 2026-09-14 --glasslog
    python3 tools/journal_report.py --selftest
"""
import bisect, collections, datetime, json, os, re, statistics, sys

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

def start_note(n):
    """Prints a note of one of the 0.64 kinds and says whether it did. `uncaught` is the last run's
    uncaught-error record, WHOLE: the kept file sits in the app's own directory, which no route
    serves, so this note is the copy that can be read. `journal` (a rotation: the archive's name is
    the whole point of the line) and `process` (the record in place; why Android says an earlier
    process ended) are one line each, never cut."""
    at = f'{datetime.datetime.fromtimestamp(n["t"]/1000):%m-%d %H:%M:%S}'
    if n.get('kind') == 'uncaught':
        print(f'  {at} uncaught:')
        for l in n.get('detail', '').splitlines(): print(f'      {l}')
        return True
    if n.get('kind') in ('journal', 'process'):
        print(f'  {at} {n["kind"]}: {n.get("detail", "")}')
        return True
    return False

def first_t(path):
    """The time of a file's first readable record, or None when it has none."""
    with open(path, encoding='utf-8') as f:
        for line in f:
            if not line.strip(): continue
            try: r = json.loads(line)
            except Exception: continue
            if isinstance(r, dict) and isinstance(r.get('t'), (int, float)): return r['t']
    return None

def chain(paths):
    """The inputs in the order they are read: the files by their first record's time (one with no
    readable record after the others), then stdin, which cannot be looked into ahead of its read.
    Returns None (said) when an input cannot be read that way.

    A file is looked into ahead of its read ONLY when there are two or more file inputs to order
    (R2, review 1b RF-8): `first_t` opens the path and the loader opens it again, so an input that can
    be read once — a process substitution `<(curl …)`, a FIFO, `/dev/stdin` — lost its head to the
    first open and the report ran on the rest. One input is read once, whatever it is; among several,
    one that is not a regular file is refused before anything is read."""
    files = list(dict.fromkeys(p for p in paths if p != '-'))
    again = sorted({p for p in paths if p != '-' and paths.count(p) > 1})
    if again:
        # read twice, every flush of it would be counted twice
        print(f'({", ".join(again)} given more than once: read once)')
    if len(files) >= 2:
        odd = [p for p in files if not os.path.isfile(p)]
        if odd:
            print(f'{", ".join(odd)}: not a regular file — among several inputs each is looked into for its first record '
                  'before it is read, and a pipe or FIFO would lose its head to that look; read it alone (or as `-`), '
                  'or save it to a file first. Nothing was read.')
            return None
        firsts = {p: first_t(p) for p in files}
        files = sorted(files, key=lambda p: (firsts[p] is None, firsts[p] or 0))
    return files + (['-'] if '-' in paths else [])

def lines_of(paths, spans):
    """Every line of the inputs, in order, as one stream. `spans` gains [name, first t, last t] per
    input as it is read (the loader fills the times)."""
    for path in paths:
        spans.append([path, None, None])
        if path == '-':
            yield from sys.stdin
        else:
            with open(path, encoding='utf-8') as f:
                yield from f

def main(paths, since_ms=0, glasslog=False):
    if isinstance(paths, str): paths = [paths]
    paths = chain(paths)
    if paths is None: return 2
    spans = []
    f = lines_of(paths, spans)
    sub, done, notes, presents, bad = {}, [], [], [], 0
    done_in = []                             # the submit's `in` flag for each `done` row, kept beside it
    done_inwait = []                         # package G (E3): the submit's `inWaitMs`, beside it (None before 0.66)
    failed = []          # completed flushes that did NOT succeed — refusals, link-loss completions
    lines_read = 0
    joined = []                              # the submit record each completed flush was joined to
    early_params = []     # link-parameter notes before --since still set the column
    early_power = None    # the last `power` note before --since: the state in force when the window opens
    early_batt = None     # and the last `battery` note before it: the level in force (never a step)
    times = []            # every record's time inside the window, in time order (the by-state report)
    recs, late = [], []
    for line in f:
        if not line.strip(): continue
        lines_read += 1
        try: r = json.loads(line)
        except Exception: bad += 1; continue
        if isinstance(r.get('t'), (int, float)):
            if spans[-1][1] is None: spans[-1][1] = r['t']
            spans[-1][2] = r['t']
        if isinstance(r.get('late'), (int, float)): late.append(r['late'])
        recs.append(r)
    # Build W, package G (E2, APK 0.66+): a record's `t` is the time of its event, and a line written a second or
    # more after it says `late` — a failed start's notes were journaled at the failure, up to 500 s on. Read in the
    # order of their events: a stable sort on `t` (a record with none keeps the time of the one before it), which
    # the bisect over `times` and the stretches below assume.
    last_t = [0]
    def at(r):
        if isinstance(r.get('t'), (int, float)): last_t[0] = r['t']
        return last_t[0]
    keyed = [(at(r), r) for r in recs]
    keyed.sort(key=lambda kr: kr[0])
    for _, r in keyed:
        if r.get('t', 0) < since_ms:
            if r.get('ev') == 'note' and r.get('kind') == 'link' and 'connection parameters' in r.get('detail', ''):
                early_params = [r]
            if r.get('ev') == 'note' and r.get('kind') == 'power': early_power = r
            if r.get('ev') == 'note' and r.get('kind') == 'battery': early_batt = r
            continue
        if isinstance(r.get('t'), (int, float)): times.append(r['t'])
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
            done_inwait.append(s.get('inWaitMs') if isinstance(s.get('inWaitMs'), int) and s.get('inWaitMs') >= 0 else None)
            done.append((r['t'], r['bytes'], r['ackMs'], s.get('via', '?'), s.get('handleMs'), s.get('assembleMs'), s.get('label', '?'), s.get('t')))
        elif ev == 'note': notes.append(r)
        elif ev == 'present': presents.append(r)
    if len(paths) > 1:
        def when(t): return f'{datetime.datetime.fromtimestamp(t/1000):%Y-%m-%d %H:%M:%S}' if t is not None else 'no readable record'
        print(f'{len(paths)} inputs, read as one journal in the order of their first record:')
        for name, t0, t1 in spans:
            print(f'  {"stdin" if name == "-" else name}: {when(t0)} → {when(t1)}')
        for (pn, _, p1), (name, t0, _) in zip(spans, spans[1:]):
            if p1 is not None and t0 is not None and t0 < p1:
                # it is known that the times overlap, not that the files share lines: two writers' files
                # interleave and share none; a live copy beside the archive that holds it shares all
                print(f'  ({"stdin" if name == "-" else name} starts before {pn} ends: lines of the two interleave; '
                      'a flush both files hold is counted twice below)')
    if bad: print(f'({bad} unreadable line(s) skipped)')
    if late:
        print(f'({len(late)} line(s) written a second or more after their event (`late`, APK 0.66+; the largest '
              f'{max(late) / 1000:.0f} s): read in the order of their events)')
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
    done_inwait = [done_inwait[i] for i in order]
    if not done:
        # a window with no flush still has the glasses' own records to show (a self-test run, a
        # quiet shell): the refusals, the transfers and the battery (2026-09-15, second review)
        print('no completed flushes')
        # a process that ended before its first flush leaves exactly these: the rotation's line,
        # the record in place, the last run's uncaught error
        for n in notes: start_note(n)
        loop_report(notes)
        refusal_report(notes)
        present_report(presents)
        battery_report(notes)
        power_report(notes, early_power, early_batt, times, since_ms)
        gate_report(notes)
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
            # P6 item 1 (LC-7): not `mirrorMs` — the scan runs in an ack's turn, which submits only when chrome
            # alone was owed; its column read zero by construction. The `loop` notes below carry it.
            for k in ('handlerMs', 'slidesMs', 'chromeMs', 'overlaysMs', 'textMs', 'truthMs', 'compressMs', 'compressN'):
                v = r.get(k)
                if isinstance(v, int) and v >= 0: parts[k].append(v)
        if parts:
            print('  split (medians / p90, n=%d): ' % len(joined) + ' · '.join(
                f'{k} {med(v)}/{sorted(v)[int(len(v)*.9)]}' for k, v in parts.items()))
            print('  (handle = handler + slides + overlays + chrome + ASSEMBLE; assemble = truth + compress + the diff and plan; '
                  'the mirror scan: the loop notes)')
    else:
        print('\nno handleMs/assembleMs in this journal (written before §32)')
    loop_report(notes)
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
        # What these words count changed with APK 0.64 (review 1a, RD-2). `proof` and `two-planes` count a
        # grown BOX once per flush (0.63 counted every planned rect that grew into it: `proof=3` for one
        # string reads 1 now) and a box already known to fail counts nothing; `dearer` counts planned
        # rects; a rect already brought to the truth is neither shipped nor counted, so the first figure
        # moved as well. The reasons no longer add up to the rects that went out as pixels, and over a
        # journal that spans the upgrade each figure is two units summed — hence --since. A word a
        # contract-1 retry un-counted arrives as `proof=0`: a zero is no refusal and is not printed.
        shown = {k: v for k, v in miss.most_common() if v}
        print(f'\ncached text: {sum(cached)} rect(s) shipped as draws over {len(cached)} flushes; '
              f'refusals by reason: {shown} (from APK 0.64 a failed proof counts once per box per flush, and a rect '
              'already brought to the truth is not shipped or counted: read a journal that spans the upgrade with --since)')
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
    for (t, b, a, via, hm, am, lab, ts), started, inw in zip(done, done_in, done_inwait):
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
            # package G (E3): "input -> done" = the time the ring event waited in the shell for a window slot
            # (`inWaitMs`, APK 0.66+) plus submit -> done; None where the journal does not say
            cur = [name, b, a, 0, 0, t - ts, (t - ts + inw) if inw is not None else None]
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
        print('  "input->done" adds the time a ring event waited in the shell for a window slot before its')
        print('  flush was submitted (APK 0.66+; "-" where the journal does not say) -- WAIT does not hold it.')
        print(f'  {"label":28s} {"bursts":>6s}  {"first bytes":>14s}  {"first wait ms":>14s}  {"input->done ms":>14s}  {"first ack ms":>14s}  {"flushes":>7s}  {"burst bytes":>11s}')
        for lab, v in sorted(bursts.items(), key=lambda kv: -len(kv[1])):
            fb = [x[1] for x in v]; fa = [x[2] for x in v]; n = [x[3] for x in v]; tb = [x[4] for x in v]
            fw = [x[5] for x in v]; fi = [x[6] for x in v if x[6] is not None]
            idone = f'{med(fi):6d} / {p90(fi):5d}' if fi else f'{"-":>6s} / {"-":>5s}'
            base = BASELINE_FIRST_BYTES.get(str(lab)) if since_ms == 0 and not split_by_in else None
            print(f'  {str(lab):28s} {len(v):6d}  {med(fb):6d} / {p90(fb):5d}  {med(fw):6d} / {p90(fw):5d}  {idone}  {med(fa):6d} / {p90(fa):5d}  {med(n):7d}  {med(tb):11d}'
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
    power_report(notes, early_power, early_batt, times, since_ms)
    gate_report(notes)
    kinds = collections.Counter(n['kind'] for n in notes)
    print(f'\nnotes: {dict(kinds)}')
    for n in notes:
        if start_note(n): continue
        # 0.64: `wear`, `case`, `sid` (what the glasses said on services the transport only reads), `rest`
        # (the rest probe and every case it did nothing in), `lowbatt` (the once-per-discharge notice)
        # 0.65 (P6, from P5's notes): `queue` (the last session's unanswered ids, ring events a start dropped, what the
        # loop's end left), `control` (the fid-ring clear, a lease call from the session before, packets not applied
        # to the mirror) and `stall` (a wide flush written at depth N) were counted above and printed nowhere
        if n['kind'] in ('link', 'panic', 'halt', 'build', 'watchdog', 'restart', 'keeper', 'probe',
                         'wear', 'case', 'sid', 'rest', 'lowbatt', 'queue', 'control', 'stall') or (n['kind'] == 'fault' and 'stall' in n['detail']):
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
    # 0.64: the cache generation count, read wherever the shell is (`Shell.pumpLedger`). Each note's
    # detail STARTS with its head; a pending, short or over-count reading with a staged strip acked is a
    # `stage` note under the subject "the stage check", counted here too.
    # APK 0.63 and earlier wrote the same three `stage` sentences from the atlas READ-BACK, under the
    # subject "the atlas check" (30 of them in the 2026-11-10 tail, measured): readings of the same
    # field that never had a second reading or a verdict. They are not this count's, and they are left
    # out by their SUBJECT, not by their place in the file — a 0.64 session with a kept atlas stages and
    # reads strips with no `count …` note before them, and a `&tail=` cut can start after the last one.
    # 0.64 never writes that subject (`Shell.ledgerVerdict` has one caller: "the stage check" or "the
    # cache count"). A 0.63 journal with Reader staging still prints the line: its "the stage check"
    # notes are readings of this count by staging's own reader (review 1a, RD-1).
    sn = [n for n in notes if n['kind'] == 'stage' and not n['detail'].startswith('the atlas check')]
    def head(h): return [n for n in an if n['detail'].startswith(h)]
    ok, short, again = head('count ok — '), head('count short — '), head('count short again — ')
    counted, late, rebased = head('re-send counted — '), head('re-send landed late — '), head('count re-based — ')
    # an unanswered reading with a strip acked is a `stage` note with no head (`Shell.launchLedgerRead`)
    unread = head('count unread — ') + head('count off — ') + [n for n in sn if n['detail'].startswith('the cache generation read did not answer')]
    unjudged = head('re-send unjudged — ')
    # R1b's other head (R1-verify 5.2 h): cached text went off with acked messages no count will judge
    ended = head('count ended — ')
    over = len(head('count over — ')) + sum(1 for n in sn if 'more generation bump(s) than' in n['detail'])
    pending = len(head('count pending — ')) + sum(1 for n in sn if 'short inside the settle window' in n['detail'])
    settled = len(head('count short (settled reading')) + sum(1 for n in sn if 'short after the settle window' in n['detail'])
    # A shortfall whose interval held staged strips ALONE writes no `atlas` note (`Shell.atlasShortfall`:
    # nothing of the atlas to name) and `raiseFault` journals nothing: its one line is the `stage` note
    # "… never landed …". With an atlas message in the interval that note ends "atlas messages were among
    # the writes" and a `count short` note of kind `atlas` is always written — counted above, not twice.
    strips = [n for n in sn if 'never landed' in n['detail'] and 'among the writes' not in n['detail']]
    if ok or short or again or counted or late or rebased or unread or unjudged or ended or pending or settled or over or strips:
        msgs = sum(int(m.group(1)) for n in ok for m in [re.match(r'count ok — (\d+) atlas message', n['detail'])] if m)
        resent = sum(int(m.group(1)) for n in short for m in [re.search(r'; (\d+) atlas message\(s\), \d+ B, go once more', n['detail'])] if m)
        print(f'  cache count: {msgs} message(s) ok in {len(ok)} reading(s), {len(short)} short ({resent} message(s) sent once more), '
              f'{len(strips)} short on staged strips alone, '
              f'{len(counted)} re-send(s) counted, {len(late)} landed late, {len(again)} not counted either time, '
              f'{pending} pending reading(s), {settled} settled short reading(s), {over} over, {len(rebased)} re-based unjudged'
              + (f', {len(unread)} unanswered' if unread else '') + (f', {len(unjudged)} re-send(s) left unjudged' if unjudged else '')
              + (f', {len(ended)} count(s) ended with messages unjudged' if ended else ''))
        for n in sorted(short + again + strips, key=lambda n: n['t'])[:3]: print(f'    {ts(n)} {n["detail"]}')
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

SIM_BUILD = re.compile(r'\bvia sim\b')

def sim_readings(notes):
    """Build W, package G (G-design §5): the `battery` notes of a SIMULATOR session — between a `build … via sim`
    note and the next `power down` or `build` note (the model's fixed level, 87 % on 2026-11-12, is no reading of
    the glasses). `notes` in time order; returns their ids, for both battery tables to leave out."""
    out, in_sim = set(), False
    for n in notes:
        k, d = n.get('kind'), n.get('detail', '')
        if k == 'build': in_sim = bool(SIM_BUILD.search(d))
        elif k == 'power' and d.startswith('down'): in_sim = False
        elif k == 'battery' and in_sim: out.add(id(n))
    return out

def battery_report(notes):
    """§49 (M0.6): the glasses' battery from `battery` notes ("glasses 61% charging").
    A stretch is a run of readings with no charging; its drain is the level lost
    over its hours. Stretches under 30 minutes or 2 points are not rated.
    Build W, package G (G-design §5): a `case` note reading `in case 1` ends a stretch as a charging reading does,
    and no stretch opens until the first reading after the next `power` note that is not `down` (in the case the
    level reads 100 % while it tops up — 2026-11-12's 7.3 %/h began there); a simulator session's readings are
    left out (`sim_readings`)."""
    pts, stream = [], []                   # the readings; and (t, level, ends a stretch) in time order
    sim, held = sim_readings(notes), False
    for n in notes:
        k, d = n.get('kind'), n.get('detail', '')
        if k == 'case' and 'in case 1' in d:
            held = True; stream.append((n['t'], None, True)); continue
        if k == 'power' and not d.startswith('down'): held = False; continue
        if k != 'battery' or id(n) in sim: continue
        m = re.match(r'glasses (\d+)%( charging)?', d)
        if m:
            pts.append((n['t'], int(m.group(1)), bool(m.group(2))))
            stream.append((n['t'], int(m.group(1)), bool(m.group(2)) or held))
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
    for t, lvl, chg in stream:
        if chg or (stretch and t - stretch[-1][0] > GAP_MS):
            flush(stretch); stretch = []
        if not chg:
            stretch.append((t, lvl))
    flush(stretch)

POWER_RE = re.compile(r'(lit|shell-silent|rest|fw-silent|down)\b(?: (\d+)%)?')
# the notes a process writes at its start, before its stack (`ShellService.onCreate`, the desktop's `runShell`:
# the rotation, the record in place, the last run's record, Android's earlier exits) — each closes the state
# the process before it left open (R2, RH-2)
START_KINDS = ('process', 'journal', 'uncaught')
POWER_ROWS = [('lit', 'lit'), ('shell-silent', 'shell SILENT screen'), ('rest', 'rest'), ('fw-silent', 'firmware Silent Mode')]

def power_report(notes, seed=None, seed_batt=None, times=None, since_ms=0):
    """0.64 (HANDOFF.md §75.4): the glasses' battery %/h by the shell's power state, from `power` notes
    (`lit`, `shell-silent`, `rest`, `fw-silent`, `down`, a live one with ` N%` when a level had been read)
    and `battery` notes (`glasses N%[ charging]`). Prints nothing for a journal with no `power` note.

    The timeline: each `power` note opens an interval that the next one closes; the last runs to the
    journal's last record. A process that ended without writing `down` is closed by the next process's
    first start note (`process`, `journal`, `uncaught` — written before its stack — or `keeper: starting`),
    at the last record before it (R2, RH-2). `seed` is the last `power` note before
    --since (the state in force when the window opens, counted from the window's start), `seed_batt`
    the last `battery` note before it (a level, never a step's upper end: what lies between it and
    the window is not known).

    Two figures per state. (1) Steps: each 1 % step between two consecutive readings, neither charging,
    is charged to the state in force when the LATER one was read, a fall as a loss and a rise as a step
    back (R2, RH-4) — unless a `down` lies between or at them, or no power note precedes them: then only
    a fall is counted, apart (a rise there is a recharge the journal never saw). (2) Entry to exit: each interval's level at its start against
    its end, summed over the intervals that have both and no charging inside; the step method carries
    up to 1 % at each boundary of a state, this one does not. The start's level is the note's own —
    except after a `down`, where the note carries the level the shell last knew (none at all in a new
    process): there the session's first reading counts, when one comes within two minutes. The end's
    level is the next note's, else the last reading before it. Hours leave out the time between a
    `charging` reading and the next that is not. A rate is printed for a state with at least 0.5 h
    and 2 % (`battery_report`'s thresholds)."""
    times = times or []
    marks, have = [], False                # (t, state, level or None)
    def mark(t, d, level=True):
        m = POWER_RE.match(d or '')
        if m: marks.append((t, m.group(1), int(m.group(2)) if m.group(2) and level else None))
        return bool(m)
    # the seed's own level was read before the window: the readings below hold it at its own time
    seeded = seed is not None and mark(max(seed['t'], since_ms), seed.get('detail', ''), level=False)
    for n in notes:
        if n.get('kind') == 'power':
            have = mark(n['t'], n.get('detail', '')) or have
        elif (n.get('kind') == 'keeper' and n.get('detail', '').startswith('starting')) or n.get('kind') in START_KINDS:
            # a process start: a state still open ended with the process before it, at its last record.
            # The first note a process writes is one of START_KINDS (R2, review 1b RH-2), written before
            # its stack: closing at `keeper: starting` alone took the start's own notes for the old
            # process's last record and charged the time the app was not running to the open state
            i = bisect.bisect_left(times, n['t'])
            marks.append((max(times[i - 1] if i > 0 else n['t'], marks[-1][0] if marks else 0), 'down', None))
    # a --since window that opens inside a state and holds no later `power` note still has that state (R2,
    # review 1b RH-3): the seed alone is a table
    if not (have or seeded): return
    end_t = max(times[-1] if times else 0, marks[-1][0], max((n['t'] for n in notes), default=0))
    iv = []                                # (t0, t1, state, entry level, exit level) — one per change of state
    for i, (t, st, lvl) in enumerate(marks):
        if iv and iv[-1][2] == st: continue                      # a seed or a process start that changed nothing
        if iv: iv[-1][1] = t; iv[-1][4] = lvl
        iv.append([t, end_t, st, lvl, None])
    rd = []                                # (t, level, charging) in time order
    sim = sim_readings(notes)              # package G (G-design §5): a simulator session's level is no reading
    for n in ([seed_batt] if seed_batt is not None else []) + notes:
        if n.get('kind') != 'battery' or id(n) in sim: continue
        m = re.match(r'glasses (\d+)%( charging)?', n.get('detail', ''))
        if m: rd.append((n['t'], int(m.group(1)), bool(m.group(2))))
    rd.sort()
    charging, c0 = [], None                # [t0, t1) spans from a charging reading to the next that is not
    for t, _, chg in rd:
        if chg and c0 is None: c0 = t
        elif not chg and c0 is not None: charging.append((c0, t)); c0 = None
    if c0 is not None: charging.append((c0, end_t))
    def overlap(a0, a1, spans): return sum(max(0, min(a1, b1) - max(a0, b0)) for b0, b1 in spans)
    def state_at(t):
        cur = None
        for t0, t1, st, _, _ in iv:
            if t0 <= t: cur = st
            else: break
        return cur
    levels = sorted([(t, l) for t, l, _ in rd] + [(t, l) for t, _, l in marks if l is not None] +
                    ([(seed['t'], int(m.group(2))) for m in [POWER_RE.match(seed.get('detail', ''))] if m and m.group(2)] if seed is not None else []))
    def level_at(t):
        i = bisect.bisect_right(levels, (t, 10**9)) - 1
        return levels[i][1] if i >= 0 else None
    downs = [(t0, t1) for t0, t1, st, _, _ in iv if st == 'down']
    lost = collections.Counter(); gap = before = 0
    first_t = seed_batt['t'] if seed_batt is not None else None
    for (ta, la, ca), (tb, lb, cb) in zip(rd, rd[1:]):
        if ca or cb or lb == la or ta == first_t: continue       # the seed is a level, never a step's upper end
        # SIGNED inside a live state (R2, review 1b RH-4): a reading that wobbles 61 → 62 → 61 is no loss,
        # so a rise takes a step back from the state it was read in. Across or inside a `down`, and before
        # the first power note, falls only: a rise there is a recharge the journal never saw.
        d = la - lb
        st = state_at(tb)
        if st is None: before += max(d, 0)
        elif st == 'down' or any(t0 < tb and t1 > ta for t0, t1 in downs): gap += max(d, 0)
        else: lost[st] += d
    hours = collections.Counter(); count = collections.Counter()
    ee_lost = collections.Counter(); ee_h = collections.Counter(); ee_n = collections.Counter()
    for i, (t0, t1, st, l0, l1) in enumerate(iv):
        if st == 'down': continue
        h = (t1 - t0 - overlap(t0, t1, charging)) / 3.6e6
        hours[st] += h; count[st] += 1
        entry = l0 if l0 is not None else level_at(t0)
        if (i == 0 and not seeded) or (i > 0 and iv[i - 1][2] == 'down'):
            # a session's start: the note's level is the one the shell last knew, the first reading is this session's
            fresh = next((l for t, l, _ in rd if t0 <= t <= min(t1, t0 + 120_000)), None)
            if fresh is not None: entry = fresh
        exit_ = l1 if l1 is not None else level_at(t1)
        if entry is not None and exit_ is not None and overlap(t0, t1, charging) == 0:
            ee_lost[st] += entry - exit_; ee_h[st] += (t1 - t0) / 3.6e6; ee_n[st] += 1
    def rate(l, h): return f'{l / h:6.1f}' if h >= 0.5 and l >= 2 else '     -'
    print('\nglasses battery by state (power notes, APK 0.64+; each 1 % step is charged to the state in force when it was read, '
          'and a rise takes a step back):')
    print(f'  {"state":22s} {"hours":>6s} {"% lost":>7s} {"%/h":>6s} {"intervals":>9s}   entry→exit: {"%":>3s} {"hours":>6s} {"%/h":>6s} {"of":>3s}')
    for st, label in POWER_ROWS:
        print(f'  {label:22s} {hours[st]:6.2f} {lost[st]:7d} {rate(lost[st], hours[st])} {count[st]:9d}'
              f'               {ee_lost[st]:3d} {ee_h[st]:6.2f} {rate(ee_lost[st], ee_h[st])} {ee_n[st]:3d}')
    print('  (entry→exit: each interval\'s own level at its start and at its end, over the intervals with both and no')
    print('   charging inside; the step figure carries up to 1 % at each boundary of a state, this one does not)')
    chg_h = sum(t1 - t0 for t0, t1 in charging) / 3.6e6
    print(f'  left out: {gap} % fell across or inside a `down` (no session between or at the two readings); '
          f'{chg_h:.2f} h of charging' + (f'; {before} % read before the first power note' if before else ''))

def gate_report(notes):
    """0.64 (HANDOFF.md §75.4 call 5; §74 A1) — a CANDIDATE, not a finding. The poll reads RIGHT's
    telemetry once a minute while the shell paints, so the journal holds `glass` notes carrying
    `presents=` (frames the glasses counted) and `transferUs=` (the LAST panel transfer's duration,
    µs). For consecutive readings 30 s or more apart: in how many the counter advanced, and in how
    many of those the duration read the same at both ends — frames counted with, perhaps, none sent
    to the panel. Perhaps: two frames a minute apart can time the same by chance. RIGHT answers a
    request twice a few ms apart, so a reading is dropped only against the last KEPT one of the same
    request id within a second (ids start over with every process, and every FLAGS_SET / op 5 reply
    carries id 0: by id alone a day of those would fold into one). Prints nothing under two readings."""
    rd, last = [], {}
    for n in notes:
        if n.get('kind') != 'glass': continue
        d = n.get('detail', '')
        if not d.startswith('R '): continue
        mp, mt, mi = re.search(r'\bpresents=(\d+)', d), re.search(r'\btransferUs=(\d+)', d), re.search(r'\bid=(\d+)', d)
        if not mp or not mt: continue
        rid = int(mi.group(1)) if mi else None
        if rid is not None and rid in last and 0 <= n['t'] - last[rid] < 1000: continue
        if rid is not None: last[rid] = n['t']
        rd.append((n['t'], int(mp.group(1)), int(mt.group(1))))
    if len(rd) < 2: return
    spans = [(a, b) for a, b in zip(rd, rd[1:]) if b[0] - a[0] >= 30_000]
    adv = [(a, b) for a, b in spans if b[1] > a[1]]
    same = [(a, b) for a, b in adv if b[2] == a[2]]
    print(f'\npanel transfers against frames counted (a CANDIDATE, not a finding; {len(rd)} telemetry readings from RIGHT): '
          f'{len(spans)} span(s) of 30 s or more, the `presents` counter advanced in {len(adv)}, '
          f'and in {len(same)} of those `transferUs` read the same at both ends')
    if spans:
        print('  (`transferUs` is the last transfer\'s duration in µs: two frames a minute apart can match by chance)')
    for a, b in same[:3]:
        print(f'  {datetime.datetime.fromtimestamp(a[0]/1000):%m-%d %H:%M:%S} → {datetime.datetime.fromtimestamp(b[0]/1000):%H:%M:%S} '
              f'presents +{b[1] - a[1]}, transferUs {a[2]} at both ends')

FIXTURES = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'fixtures', 'journal_report')

LOOP_ENTRY = re.compile(r'(?:^|; |: )(\S+) n=(\d+) total=(\d+(?:\.\d+)?) max=(\d+(?:\.\d+)?)')

def loop_report(notes):
    """APK 0.65 (P6 item 1, the loop-cost sweep's LC-7): the shell's `loop` notes, one a minute while anything ran
    (`Shell.noteLoopCost`): per message class the count, the total and the maximum handler time in ms, the pumps and
    the mirror scans apart. Summed over the window: every message the loop handled, flush or not. The scan's time
    is inside the `FlushDone` handler's; the submit lines' `mirrorMs` reached a row only when the ack's own turn
    flushed (1 row in 5,214 on 2026-11-10), so the split above no longer prints it. Prints nothing without one."""
    ln = [n for n in notes if n.get('kind') == 'loop']
    if not ln: return
    acc = collections.OrderedDict()
    secs = 0
    rows = None                 # P6 item 5: rows the mirror scans compared (`rows=`, both lenses), once 0.65 writes it
    for n in ln:
        d = n.get('detail', '')
        m = re.match(r'over (\d+) s: ', d)
        if m: secs += int(m.group(1))
        r = re.search(r' rows=(\d+)', d)
        if r: rows = (rows or 0) + int(r.group(1))
        for name, k, tot, mx in LOOP_ENTRY.findall(d):
            a = acc.setdefault(name, [0, 0.0, 0.0])
            a[0] += int(k); a[1] += float(tot); a[2] = max(a[2], float(mx))
    print(f'\nloop time ({len(ln)} `loop` note(s) over {secs / 60:.1f} min, APK 0.65+; handler time per message class, '
          'whether its turn flushed or not; ms):')
    print(f'  {"class":28s} {"n":>7s} {"total":>10s} {"mean":>8s} {"max":>8s}')
    rest = [k for k in acc if k not in ('pump', 'mirror')]
    for k in sorted(rest, key=lambda k: -acc[k][1]) + [k for k in ('pump', 'mirror') if k in acc]:
        n, tot, mx = acc[k]
        name = {'pump': 'pump (every turn)', 'mirror': 'mirror scan (in FlushDone)'}.get(k, k)
        print(f'  {name:28s} {n:7d} {tot:10.1f} {tot / n if n else 0:8.1f} {mx:8.1f}')
    if rows is not None and acc.get('mirror', [0])[0]:
        print(f'  mirror scans compared {rows} rows, {rows / acc["mirror"][0]:.0f} a scan (every row of both lenses is 960 at height 480)')
    handlers = sum(acc[k][1] for k in rest)
    print(f'  handlers {handlers / 1000:.1f} s + pumps {acc.get("pump", [0, 0.0])[1] / 1000:.1f} s on the loop')

def selftest():
    """The report's own checks (0.64 R1; review 1a, RD-1 and RD-2): `main` run over the synthetic journals
    under tools/fixtures/journal_report/ and its text read. Every fixture line was written by hand from
    the shell's own note strings (`Shell.ledgerVerdict`, `atlasShortfall`; `886d655` for APK 0.63's) — none
    is a line of a real journal. Reads those files only and writes nothing. Each case names what the
    report has to say; exit status 0 when every case holds, 1 when one does not.
      fixA  APK 0.63: one cache write and the read-back's own `stage` note (subject "the atlas check")
      fixB  APK 0.64: a shortfall whose interval held staged strips alone (no `atlas` note is written)
      fixC  APK 0.64: one flush whose miss counts hold a zero entry (`proof=0,two-planes=1`)
      fixD  a journal that spans the upgrade, then a mixed shortfall (a strip and an atlas message), an
            unanswered reading with a strip acked, and a re-send left unjudged"""
    import io, contextlib
    def report(name):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            main([os.path.join(FIXTURES, name)])
        return buf.getvalue()
    def line(out, head): return next((l for l in out.splitlines() if l.strip().startswith(head)), '')
    results = []
    def case(name, holds, said): results.append((name, bool(holds), said))

    a = report('fixA.jsonl')
    case('fixA: a 0.63 journal (the read-back\'s "the atlas check" notes) prints no cache count line',
         'atlas traffic: 1 message(s)' in a and 'cache count:' not in a, line(a, 'cache count:') or line(a, 'atlas traffic:'))
    b = line(report('fixB.jsonl'), 'cache count:')
    case('fixB: a shortfall of staged strips alone is counted and named',
         all(w in b for w in ('1 message(s) ok in 1 reading(s)', ' 0 short (0 message(s) sent once more)', ' 1 short on staged strips alone',
                              ' 4 pending reading(s)', ' 1 settled short reading(s)')), b)
    c = line(report('fixC.jsonl'), 'cached text:')
    case('fixC: the miss counts say what they count and hold no zero entry',
         "refusals by reason: {'two-planes': 1}" in c and 'once per box per flush' in c and 'proof' not in c.split('(from')[0]
         and 'pixels by reason' not in c, c)
    d = line(report('fixD.jsonl'), 'cache count:')
    case('fixD: a journal that spans the upgrade counts 0.64\'s readings alone',
         all(w in d for w in (' 1 pending reading(s)', ' 1 settled short reading(s)', ' 0 over,')), d)
    case('fixD: a mixed interval is one shortfall, the atlas note\'s',
         ' 1 short (1 message(s) sent once more)' in d and ' 0 short on staged strips alone' in d, d)
    case('fixD: an unanswered reading with a strip acked and a re-send left unjudged are counted',
         ' 1 unanswered' in d and ' 1 re-send(s) left unjudged' in d, d)
    case('USAGE says to pass --since at the upgrade', re.search(r'--since[^\n]*upgrade|upgrade[^\n]*--since', USAGE), '(the usage text)')
    e = line(report('fixE.jsonl'), 'cache count:')
    case('fixE (R1-verify 5.2 h): a count that ended with messages unjudged is counted beside a re-send left unjudged',
         ', 1 count(s) ended with messages unjudged' in e and ', 1 re-send(s) left unjudged' in e, e)

    # R2 (review 1b RH-2, RH-3, RH-4, RF-8): the by-state table and the inputs. Fixtures written by the review's
    # readers, synthetic notes only (power-*.jsonl).
    def run(paths, since=0):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = main([p if p.startswith('/dev/') else os.path.join(FIXTURES, p) for p in paths], since)
        return code, buf.getvalue()
    def row(out, state):
        """[hours, % lost] of a state's row in the by-state table, or None."""
        cols = line(out.split('glasses battery by state')[-1] if 'glasses battery by state' in out else '', state + ' ').split()
        return cols[1:3] if len(cols) > 2 else None
    a = run(['power-A-restart-no-down.jsonl'])[1]
    case('RH-2 fixture A: a process that ended without `down` is closed at its own last record, not at the next '
         'process\'s `keeper: starting` (lit 2.00 h and 5 %, not 11.00 h)', row(a, 'lit') == ['2.00', '5'], line(a, 'lit '))
    f = run(['power-F-open-rest.jsonl'], since=1800003600000 + 300_000)[1]
    case('RH-3 fixture F with --since five minutes into the rest: the by-state table prints (rest 1.92 h)',
         row(f, 'rest') == ['1.92', '2'], line(f, 'rest ') or '(no by-state table)')
    b = run(['power-B-rising.jsonl'])[1]
    case('RH-4 fixture B: a rise is a step back (61 → 62 → 61 → 62 → 61 is lit 0 %, not 2)', row(b, 'lit') == ['1.00', '0'], line(b, 'lit '))
    g = run(['power-G-unseen-recharge.jsonl'])[1]
    case('RH-4 fixture G: a recharge the journal never saw is left out across the `down` (lit 15 %, not the '
         'running minimum\'s 10)', (row(g, 'lit') or [None, None])[1] == '15', line(g, 'lit '))
    case('RH-4: the header says a rise takes a step back', 'a rise takes a step back' in g, line(g, 'glasses battery by state'))
    halves = ['power-E1.jsonl', 'power-E2.jsonl']
    by_path = run(halves)[1]
    r, w = os.pipe()
    try:
        for h in halves:
            with open(os.path.join(FIXTURES, h), 'rb') as fh: os.write(w, fh.read())
        os.close(w); w = None
        piped = run(['/dev/fd/%d' % r])[1]
    finally:
        if w is not None: os.close(w)
        os.close(r)
    table = lambda out: out[out.find('glasses battery by state'):] if 'glasses battery by state' in out else '(no by-state table)'
    case('RF-8: one input that can be read once (`<(cat E1 E2)`) reports what the two files by path report',
         table(piped) == table(by_path) and 'glasses battery by state' in by_path, table(piped).splitlines()[0][:100])
    r, w = os.pipe()
    try:
        os.write(w, b'{"t": 1, "ev": "note", "kind": "mark", "detail": "x"}\n'); os.close(w); w = None
        code, out = run([halves[0], '/dev/fd/%d' % r])
        unread = os.read(r, 100)
    finally:
        if w is not None: os.close(w)
        os.close(r)
    case('RF-8: among several inputs one that is not a regular file is refused before anything is read (exit 2)',
         code == 2 and 'not a regular file' in out and unread, out.strip().splitlines()[-1] if out.strip() else '(nothing said)')
    o = run(['power-A-restart-no-down.jsonl', 'power-E1.jsonl'])[1]
    case('RF-8: the overlap line says the lines interleave and a flush both files hold is counted twice',
         'lines of the two interleave; a flush both files hold is counted twice' in o, line(o, '('))
    # 0.65 P6: notes-p5-loop.jsonl — one of each note P5 added (texts from the code at 43dc00f, values filled in by
    # hand) and one `loop` note (`Shell.noteLoopCost`), behind one flush so the notes list prints.
    p = run(['notes-p5-loop.jsonl'])[1]
    listed = p.split('\nnotes: ', 1)[-1] if '\nnotes: ' in p else ''
    heads = ('queue: 2 ring event(s) that came during the start dropped', 'queue: 3 flush(es) of the last session not answered',
             'queue: the loop ended with 2 message(s) unhandled', 'link: the input reserve yields',
             'control: taking the lease back asked during a start', 'control: fid-ring clear not sent at the session start',
             'control: the fid-ring clear went unacked', 'control: the fid-ring clear at the session start was not confirmed',
             'control: 4 packet(s) of the ended session', 'keeper: a session rebuild asked in a session that has ended',
             'keeper: flags 0x4 not armed', 'keeper: flags above bit 2 not armed', 'keeper: DRAW2 not armed',
             'stall: wide flush written at depth 1')
    case('P6: every note P5 added is printed in the notes list', all(h in listed for h in heads),
         'missing: ' + str([h for h in heads if h not in listed]))
    case('P6 (LC-7): the loop notes print per class, the scan apart, and the split prints no mirrorMs',
         line(p, 'SaveTick ').split()[1:5] == ['1', '120.0', '120.0', '120.0'] and line(p, 'mirror scan (in FlushDone)').split()[-4:] == ['11', '110.2', '10.0', '12.0']
         and 'mirrorMs' not in line(p, 'split (') and 'loop:' not in listed, line(p, 'mirror scan') or line(p, 'split ('))
    case('P6 (LC-2): the rows the scans compared print, per scan', '1320 rows, 120 a scan' in line(p, 'mirror scans compared'),
         line(p, 'mirror scans compared'))
    # Build W, package G (the lead's decision H; G-design §5, §2): the battery line and the event times. Synthetic notes.
    c = run(['battery-case-in.jsonl'])[1]
    s = line(c, datetime.datetime.fromtimestamp((1800000000000 + 3060000) / 1000).strftime('%m-%d %H:%M'))
    case('G §5 (1): readings in the charging case do not open a stretch; it opens after the next power note '
         '(98 → 82 % over 4.0 h = 4.0 %/h, not 100 → 82 from the case)', '98→ 82' in s and s.split()[-1] == '4.0', s or line(c, 'glasses battery ('))
    m = run(['battery-sim.jsonl'])[1]
    case('G §5 (2): a simulator session\'s battery reading is in neither table (4 readings; 4 % left out across the '
         '`down`, not 13)', '(4 changes' in line(m, 'glasses battery (') and '  left out: 4 % fell' in m,
         line(m, 'glasses battery (') + ' / ' + line(m, 'left out:'))
    e2 = run(['late-e2.jsonl'])[1]
    case('G §2: a line written late is read in the order of its event (lit 3.00 h: process 1 closed at its last '
         'record, 2 h, not at the late line\'s 1.5 h), and the report says how many came late',
         row(e2, 'lit') is not None and row(e2, 'lit')[0] == '3.00' and '1 line(s) written a second or more after' in e2,
         line(e2, 'lit ') + ' / ' + line(e2, '1 line(s)'))
    i3 = run(['inwait-e3.jsonl'])[1]
    case('G §3: a gesture that waited 2,000 ms in the shell for a slot reads input->done 2900 beside its wait of 900',
         line(i3, 'WINDOW ').split()[5:9] == ['900', '/', '900', '2900'], line(i3, 'WINDOW '))
    missed = 0
    for name, holds, said in results:
        print(('ok    ' if holds else 'MISS  ') + name + ('' if holds else '\n        said: ' + (said or '(no such line)')))
        missed += not holds
    print('selftest: %d of %d' % (len(results) - missed, len(results)))
    return 1 if missed else 0

USAGE = '''usage: journal_report.py [PATH ...] [-] [--since YYYY-MM-DD[THH:MM]] [--glasslog]
       journal_report.py --selftest

Reads the phone's journal (`curl '.../journal?token=...&tail=2000000' | journal_report.py -`; drop
`&tail=` only from APK 0.61 on, HANDOFF.md §71) and prints the
per-gesture, per-band and per-glass numbers `WINDOWS.md` §6 and `REMINDER.md` price with.
Several PATHs (the archives a rotation set aside, APK 0.64 on, and the live file) are read as one
journal in the order of their first record; `-` (stdin, at most once) is read last. No PATH = stdin.
Among several PATHs each must be a regular file (a pipe or `<(…)` is read alone, or as `-`).
    curl -s '.../journals?token=...'                                   the live file and its archives
    curl -s '.../journal?token=...&name=journal-YYYYMMDD-HHMMSS.jsonl' one archive (&tail=N works)
    python3 tools/journal_pull.py --live                               copy the archives and the live file to this machine
A journal that spans the upgrade to APK 0.64: pass --since with the time of the upgrade. The `cached
text:` line's counts changed unit there (a failed proof counts once per box per flush from 0.64 on, and
a rect already brought to the truth is not counted), so one sum over both builds is two units added.
--selftest runs the report's own checks over tools/fixtures/journal_report/ (synthetic journals).
Exit status: 0 a report (an empty one included) or a self-test with every case held, 1 a self-test
case missed, 2 the input was not a journal, or among several inputs one was not a regular file (nothing read).'''

if __name__ == '__main__':
    args = sys.argv[1:]
    if '--help' in args or '-h' in args:
        print(USAGE); raise SystemExit(0)
    if '--selftest' in args:
        # its own checks over the fixtures beside it; no journal is read (said, not ignored, when one is named)
        if len(args) > 1:
            print(USAGE); raise SystemExit(f'\n--selftest takes no other argument: {[a for a in args if a != "--selftest"]}')
        raise SystemExit(selftest())
    since = 0
    if '--since' in args:
        i = args.index('--since')
        since = int(datetime.datetime.fromisoformat(args[i + 1]).timestamp() * 1000)
        del args[i:i + 2]
    show_glass = '--glasslog' in args
    if show_glass: args.remove('--glasslog')
    # an unknown flag used to be taken as the path (2026-09-16 review): say so rather than report
    # on the wrong input. Several paths are one journal since 0.64 (a second path was refused
    # before); stdin can be read once.
    bad_args = [a for a in args if a.startswith('-') and a != '-']
    if bad_args or args.count('-') > 1:
        print(USAGE); raise SystemExit(f'\nunrecognised argument(s): {bad_args or ["- given more than once"]}')
    raise SystemExit(main(args or ['-'], since, show_glass) or 0)
