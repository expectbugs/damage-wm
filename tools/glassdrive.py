#!/usr/bin/env python3
"""Drive a running Damage shell through its replica WebSocket and snapshot
the mirror between steps — the §28.2/§29.2 live-walk instrument, rebuilt
(2026-09-05, `HANDOFF.md` §33). Works against the PHONE (the real glasses) or
a scratch-home sim instance; the server is the same on both.

    python3 tools/glassdrive.py HOST TOKEN [--port 7403] [--pace 2.0] STEP...

STEPs, in order: a gesture (tap double up down hold release), `wait:SECONDS`,
`pace:SECONDS` (change the gap between the following gestures; the switcher
chord is `pace:0.3 hold release double pace:2.5`),
`snap:PATH.png` (both lenses side by side, 1×, from the mirror as it stands),
`status` (print the last status frame), `probe:NAME=VALUE` (a Phase 0 measurement
probe for the host's transport, `HANDOFF.md` §49 — `probe:diag=show|hide` the
firmware's diagnostic overlay, `probe:logger=on|off` the glasses' own log stream
into the journal as `glasslog` notes, `probe:phy=2m|1m` the radio PHY ask; APK
0.45+; on a Damage build (`FIRMWARE.md` §3, APK 0.46+): `probe:telemetry=read`,
`probe:cache=info`, `probe:flags=clear|probe|0xNNNN`, `probe:selftest=begin|end|step:HEX|live:HEX`, `probe:cachesize=64..160`,
`probe:diag=clear` — mode 7 sub 0: the sticky diagnostics, the fid ring and the refusal record),
`selftest:PATH.json` (a conformance vector — `firmware/vectors/v1-*.json`, `v2-*.json` —
driven through the glasses' self-test step by step; after each step RIGHT's telemetry is
read from the host's `/log` and its scratch CRC, refusal and — for a v2 vector — the
refusal record's mode and reason compared with the vector's expectations; a v2 vector's
`flags`/`cachesize` ops go first as probes and a cache write is sent live, as the fork's
`host/run_self_test.py` does; the `token` is the same as the WebSocket's). Gestures are paced by --pace seconds
so each flush is isolated in the journal (§31.1's method). Every gesture is
echoed with a timestamp so the journal's flushes can be matched to it.

⚠ §29.2 binds: one step per snap in any window with an irreversible row; never
scroll in Music's root (scroll = volume); never tap in Settings on a staged
row; Games and Files taps change real state. Stdlib + `websockets` only.
"""
import asyncio, json, struct, sys, time, zlib

W, H = 640, 480
STRIDE = (W + 1) // 2
GESTURES = {'tap', 'double', 'up', 'down', 'hold', 'release'}

def png(path, panels):
    """Both 4bpp panels side by side as an 8-bit grayscale PNG, true 1×."""
    rows = []
    for y in range(H):
        row = bytearray([0])
        for arm in (0, 1):
            src = panels[arm][y * STRIDE:(y + 1) * STRIDE]
            for b in src:
                row.append((b >> 4) * 17); row.append((b & 15) * 17)
        rows.append(bytes(row))
    raw = b''.join(rows)
    def chunk(t, d): return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    data = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 2 * W, H, 8, 0, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw, 6)) + chunk(b'IEND', b'')
    open(path, 'wb').write(data)

def log_tail(host, port, token, n=80):
    """The host's recent log lines (`/log?token=&tail=N`, no adb)."""
    import urllib.request
    with urllib.request.urlopen(f'http://{host}:{port}/log?token={token}&tail={n}') as r:
        return r.read().decode('utf-8', 'replace').splitlines()

async def selftest(ws, host, port, token, path):
    """Drive one vector file through the glasses' self-test (FIRMWARE.md §3, mode 16) and compare
    RIGHT's reported scratch CRC and refusal per step with the vector's expectations. The lease
    and cache vectors have no self-test form. Every wait here is pacing between reads; a step
    whose telemetry never advances is reported, never retried forever."""
    import re
    vec = json.load(open(path))
    ops_all = [op for st in vec['steps'] for op in st['ops']]
    if vec['name'] == 'v1-lease' or any(op.get('lease') == 'release' for op in ops_all):
        print(f'{vec["name"]}: no self-test form (a lease release or lapse inside it)'); return
    async def probe(name, value):
        await ws.send(json.dumps({'t': 'probe', 'name': name, 'value': value}))
    def right_telemetry():
        for line in reversed(log_tail(host, port, token)):
            m = re.search(r'glass telemetry from RIGHT: (.*)$', line)
            if m: return dict(kv.split('=', 1) for kv in m.group(1).split() if '=' in kv)
        return None
    seen_id = [-1]
    async def read_fresh(tries=6):
        """A record RIGHT answered to a read sent from HERE: the request id rises with every read,
        so a line left in the log by the shell's own read (or by the previous step) is never taken
        for this one (2026-09-15, the second review — a step with no count of its own used to pass
        on the record before it)."""
        for _ in range(tries):
            await probe('telemetry', 'read'); await asyncio.sleep(0.8)
            got = right_telemetry()
            if got and int(got.get('id', -1)) > seen_id[0]:
                seen_id[0] = int(got['id'])
                return got
        return None
    def is_live(m): return (int(m[:2], 16) & 0x7f) in (12, 19)   # a cache write goes to the live cache the steps read
    # the flags in force now: a vector's flag set is added to them and they are put back afterwards, so
    # the session's own wish (PRESENTED, PROBE) survives the run (2026-09-15 review)
    # the record fields 23-25 are STICKY until the next refusal or a mode-7 sub-0: an earlier
    # vector's (or the shell's own) refusal would be read as this step's, so the run starts from a
    # cleared record and says so if it is not clear (2026-09-15, the second review)
    await probe('diag', 'clear'); await asyncio.sleep(0.6)
    # a run that could not START is a FAILURE, not a quiet zero (2026-09-16 review): the third
    # review made a differing step exit non-zero, but a vector that never executed a single step
    # still exited 0 — the shape `run_self_test.py` had fixed at §61.1 item 9
    before = await read_fresh() or {}
    if not before:
        print(f'{vec["name"]}: FAIL — RIGHT answered no telemetry; is this a Damage build with the self-test? (nothing run)'); return 1
    if int(before.get('refMode', 0)) != 0:
        print(f'  FAIL — the refusal record did not clear (mode {before.get("refMode")} reason {before.get("refReason")}): the comparisons below would read it as a step\'s — stopping')
        return 1
    flags_before = int(before.get('flags', '0x0'), 16)
    gen_before = int(before.get('cacheGen', 0))
    vec_flags = [int(op['flags']) for op in ops_all if 'flags' in op]
    # a v2 vector's control ops run before the begin, so DRAW2 and the cache size are in force
    # for every step (the same order as the fork's host/run_self_test.py)
    for op in ops_all:
        if 'flags' in op: await probe('flags', '0x%04x' % (flags_before | int(op['flags']))); await asyncio.sleep(0.5)
        elif 'cachesize' in op: await probe('cachesize', str(int(op['cachesize']))); await asyncio.sleep(0.5)
    if any(is_live(m) for st in vec['steps'] for m in (op['msg'] for op in st['ops'] if 'msg' in op)):
        print(f'  {vec["name"]} writes the live texture cache: the shell drops its atlas for this session (cached text is pixels until the next session or a Cached text toggle)')
    await probe('selftest', 'begin'); await asyncio.sleep(0.6)
    begun = await read_fresh()
    if not begun or int(begun.get('stSteps', -1)) != 0:
        print(f'  FAIL — the begin was refused or never ran (stSteps={begun.get("stSteps") if begun else None}, expected 0): the lease, Silent Mode or the scratch — stopping')
        return 1
    fails = 0; steps_seen = 0; live_writes = 0
    for i, st in enumerate(vec['steps']):
        msgs = [op['msg'] for op in st['ops'] if 'msg' in op]
        steps = [m for m in msgs if not is_live(m)]          # what the self-test counts (fields 20-22)
        if any('tick' in op for op in st['ops'] if 'msg' not in op) and i > 0:
            print(f'  step {i}: a tick op has no self-test form — skipped')
        for m in msgs:
            if is_live(m):
                await probe('selftest', 'live:' + m); await asyncio.sleep(0.6 + len(m) / 40000)
                live_writes += 1
                continue
            await probe('selftest', 'step:' + m); await asyncio.sleep(0.4 + len(m) / 40000)
        want = st['expect']
        got = None
        for attempt in range(6):                      # pacing: the step runs on the deferred handler after the ack
            got = await read_fresh(tries=1)
            if got and int(got.get('stSteps', 0)) >= steps_seen + len(steps): break
        steps_seen += len(steps)
        # exactly the steps sent: fewer means one never ran, more means something else is stepping
        # the self-test, and either way the CRC below is another step's (2026-09-15, second review)
        if not got or int(got.get('stSteps', -1)) != steps_seen:
            fails += 1; print(f'  FAIL step {i}: the glasses report stSteps={got.get("stSteps") if got else None}, expected exactly {steps_seen}'); continue
        crc_ok = got.get('stCrc') == want['R']
        # the refusal field is the last STEP's: a live cache write is no step, and a step that carried
        # no self-test message says nothing new about it
        last_step = max((k for k, m in enumerate(msgs) if not is_live(m)), default=None)
        rc_ok = last_step is None or (got.get('stRefused') == ('1' if want['rc']['R'][last_step] != 0 else '0'))
        # the refusal record's mode and reason (its sequence is the live count; not compared).
        # It is compared whenever the vector HAS one: a v1 vector's refusals are recorded
        # by a Phase 2 build too (the dispatcher records every image-lane refusal, v1 modes
        # included), and gating on the VECTOR's contract skipped exactly the vectors that run
        # first (2026-09-15, the third review; run_self_test.py compares them all)
        ref = want.get('ref', {}).get('R')
        ref_ok = True
        if ref is not None:
            got_ref = (int(got.get('refMode', 0)), int(got.get('refReason', 0)))     # absent fields = no refusal recorded
            ref_ok = got_ref == (ref[0], ref[1])
        print(f'  {"PASS" if crc_ok and rc_ok and ref_ok else "FAIL"} step {i}: scratch {got.get("stCrc")} (expected {want["R"]}), refused {got.get("stRefused")}'
              + (f', refusal record {got.get("refMode", 0)}/{got.get("refReason", 0)} (expected {ref[0]}/{ref[1]})' if ref is not None else ''))
        fails += not (crc_ok and rc_ok and ref_ok)
    await probe('selftest', 'end')
    # every live cache write the vector sent must have changed the cache: the generation counts the
    # writes the firmware TOOK, and the ack says nothing (it precedes the decode)
    if live_writes:
        after = await read_fresh()
        gen_after = int(after.get('cacheGen', 0)) if after else -1
        if gen_after < gen_before + live_writes:
            fails += 1
            print(f'  FAIL: {live_writes} live cache write(s) sent, the generation moved {gen_before} -> {gen_after}: the glasses refused one'
                  + (f' (record: mode {after.get("refMode")} reason {after.get("refReason")})' if after else ''))
    if vec_flags:
        # restore the session's WISH, not the set in force: DRAW2 belongs to the session (the
        # transport keeps it on the wire for this one), and asking for bit 2 by hand would lift a
        # hold-back the keeper had decided on (2026-09-15, second review)
        wish = flags_before & ~0x0004
        await probe('flags', '0x%04x' % wish); await asyncio.sleep(0.5)
        print(f'  flags restored to the session\'s wish 0x{wish:04x} (DRAW2 stays armed for this session if it was)')
    # a vector whose steps all got skipped is not a pass either: say so and fail
    if steps_seen == 0:
        fails += 1
        print(f'  FAIL — no step of {vec["name"]} ran on the glasses')
    print(f'{vec["name"]}: {"all steps match on RIGHT" if not fails else f"{fails} step(s) differ"} (LEFT runs the same steps but cannot report — FIRMWARE.md §3)')
    return fails

async def main():
    fails = [0]          # the run's exit status: any selftest step that differed
    bad_status = [0]     # status frames that would not parse
    import websockets
    args = [a for a in sys.argv[1:]]
    host, token = args[0], args[1]
    port, pace, steps = 7403, 2.0, []
    i = 2
    while i < len(args):
        if args[i] == '--port': port = int(args[i + 1]); i += 2
        elif args[i] == '--pace': pace = float(args[i + 1]); i += 2
        else: steps.append(args[i]); i += 1
    panels = [bytearray(STRIDE * H), bytearray(STRIDE * H)]
    got = [0, 0]
    status = {}
    stop = asyncio.Event()

    async with websockets.connect(f'ws://{host}:{port}/ws?token={token}', max_size=None) as ws:
        async def reader():
            try:
                async for m in ws:
                    if isinstance(m, (bytes, bytearray)):
                        arm, y0, rows = struct.unpack('<BHH', m[:5])
                        body = m[5:5 + rows * STRIDE]
                        panels[arm][y0 * STRIDE:y0 * STRIDE + len(body)] = body
                        got[arm] += 1
                    else:
                        try: status.update(json.loads(m))
                        except Exception as e:
                            # never silent: a later `status` step would print stale state as if it
                            # were current (2026-09-15, the third review)
                            bad_status[0] += 1
                            print(f'[status frame unreadable ({e}); {bad_status[0]} so far — the printed status may be stale]')
            except Exception as e:
                print(f'[reader ended: {e}]')
            finally:
                stop.set()
        rt = asyncio.create_task(reader())
        t0 = time.time()
        for s in steps:
            if stop.is_set(): print('connection gone; stopping'); break
            if s in GESTURES:
                await ws.send(json.dumps({'t': 'input', 'ev': s}))
                print(f'{time.strftime("%H:%M:%S")} +{time.time()-t0:6.1f}s  {s}')
                await asyncio.sleep(pace)
            elif s.startswith('wait:'):
                await asyncio.sleep(float(s[5:]))
            elif s.startswith('pace:'):          # the §1.3 chord needs ~0.3 s between its three events
                pace = float(s[5:])
            elif s.startswith('snap:'):
                png(s[5:], panels)
                print(f'{time.strftime("%H:%M:%S")} +{time.time()-t0:6.1f}s  snap -> {s[5:]} (frames L={got[0]} R={got[1]})')
            elif s.startswith('probe:') and '=' in s:
                name, value = s[6:].split('=', 1)
                await ws.send(json.dumps({'t': 'probe', 'name': name, 'value': value}))
                print(f'{time.strftime("%H:%M:%S")} +{time.time()-t0:6.1f}s  probe {name}={value}')
                await asyncio.sleep(0.5)
            elif s == 'status':
                print(f'status: {status}')
            elif s.startswith('selftest:'):
                fails[0] += await selftest(ws, host, port, token, s[9:]) or 0
            else:
                raise SystemExit(f'unknown step {s!r}')
        rt.cancel()
    # every other gate in this repo exits non-zero on a disagreement; the one that runs against
    # the actual glasses used to exit 0 however it went (2026-09-15, the third review)
    if bad_status[0]:
        # counted and printed since the third review, but it never reached the exit status: a run
        # in which every status frame was unreadable still said 0 (2026-09-16 review)
        print(f'{bad_status[0]} status frame(s) were unreadable — the printed state was stale')
        fails[0] += bad_status[0]
    if stop.is_set():
        print('the link to the phone ended before the steps were done — the run is not a pass')
        fails[0] += 1
    return 1 if fails[0] else 0

raise SystemExit(asyncio.run(main()))
