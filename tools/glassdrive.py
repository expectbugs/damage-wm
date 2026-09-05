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
`status` (print the last status frame). Gestures are paced by --pace seconds
so each flush is isolated in the journal (§31.1's method). Every gesture is
echoed with a timestamp so the journal's flushes can be matched to it.

⚠ §29.2 binds: one step per snap in any window with a destructive row; never
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
            elif s == 'status':
                print(f'status: {status}')
            else:
                raise SystemExit(f'unknown step {s!r}')
        rt.cancel()

asyncio.run(main())
