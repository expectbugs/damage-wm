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
`probe:cache=info`, `probe:flags=clear|probe|0xNNNN`, `probe:selftest=begin|end|step:HEX`),
`selftest:PATH.json` (a conformance vector — `firmware/vectors/v1-*.json` — driven
through the glasses' self-test step by step; after each step RIGHT's telemetry is read
from the host's `/log` and its scratch CRC and refusal compared with the vector's
expectations; the `token` is the same as the WebSocket's). Gestures are paced by --pace seconds
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
    if vec['name'] in ('v1-lease', 'v1-cache'):
        print(f'{vec["name"]}: no self-test form (lease or cache vector)'); return
    async def probe(name, value):
        await ws.send(json.dumps({'t': 'probe', 'name': name, 'value': value}))
    def right_telemetry():
        for line in reversed(log_tail(host, port, token)):
            m = re.search(r'glass telemetry from RIGHT: (.*)$', line)
            if m: return dict(kv.split('=', 1) for kv in m.group(1).split() if '=' in kv)
        return None
    await probe('selftest', 'begin'); await asyncio.sleep(0.6)
    fails = 0; steps_seen = 0
    for i, st in enumerate(vec['steps']):
        msgs = [op['msg'] for op in st['ops'] if 'msg' in op]
        if any('tick' in op or 'lease' in op for op in st['ops'] if 'msg' not in op) and i > 0:
            print(f'  step {i}: a tick/lease op has no self-test form — skipped')
        for m in msgs:
            await probe('selftest', 'step:' + m); await asyncio.sleep(0.4 + len(m) / 40000)
        want = st['expect']
        got = None
        for attempt in range(6):                      # pacing: the step runs on the deferred handler after the ack
            await probe('telemetry', 'read'); await asyncio.sleep(0.8)
            got = right_telemetry()
            if got and int(got.get('stSteps', 0)) >= steps_seen + len(msgs): break
        steps_seen += len(msgs)
        if not got or int(got.get('stSteps', 0)) < steps_seen:
            fails += 1; print(f'  FAIL step {i}: the glasses report stSteps={got.get("stSteps") if got else None}, expected {steps_seen}'); continue
        crc_ok = got.get('stCrc') == want['R']
        # the refusal field is the LAST step's: a step that carried no message says nothing new about it
        rc_ok = (not msgs) or (got.get('stRefused') == ('1' if want['rc']['R'][-1] != 0 else '0'))
        print(f'  {"PASS" if crc_ok and rc_ok else "FAIL"} step {i}: scratch {got.get("stCrc")} (expected {want["R"]}), refused {got.get("stRefused")}')
        fails += not (crc_ok and rc_ok)
    await probe('selftest', 'end')
    print(f'{vec["name"]}: {"all steps match on RIGHT" if not fails else f"{fails} step(s) differ"} (LEFT runs the same steps but cannot report — FIRMWARE.md §3)')

async def main():
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
                        except Exception: pass
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
                await selftest(ws, host, port, token, s[9:])
            else:
                raise SystemExit(f'unknown step {s!r}')
        rt.cancel()

asyncio.run(main())
