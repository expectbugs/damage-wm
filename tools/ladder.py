#!/usr/bin/env python3
"""Set the firmware ladder's stage on the phone by hand, and arm it now.

    python3 tools/ladder.py state     # what the glasses' right lens reports now (flags), nothing changed
    python3 tools/ladder.py 4         # store stage 4 and start a session so it arms (the display blanks ~2 s)
    python3 tools/ladder.py 5         # store stage 5 (the transitions and effects) and arm it

Context: Damage is Adam's own window manager for his own glasses, phone and PC on his own network (display and
interface work on a personal device). The phone keeps the ladder's stage (`DAILY.md`, "The firmware ladder") and
arms it at each session start; until 2026-11-21 the only way to change it was a probe typed into a Claude
session. This is the same three messages over the phone's replica WebSocket — `c1=N`, `rest=on`, `rest=off` —
for a day when no session is at hand: stage 4 is the way back if stage 5 misbehaves.

It reads `token`, `phoneHost` and `replicaPort` from ~/.damage/config.json and never prints the token. Needs the
system Python's `websockets`. No flag here writes anything but those probes; `state` sends none.
"""
import asyncio, json, os, re, sys, time, urllib.request

FLAGS = {0x3c: 'stage 4 (local input, no effects)', 0x7c: 'stage 5 (effects armed)'}


def config():
    c = json.load(open(os.path.expanduser('~/.damage/config.json')))
    return c['phoneHost'], int(c.get('replicaPort', 7403)), c['token']


def newest_flags(host, port, token):
    """The newest telemetry line's time and flags from the phone's log ring, or None."""
    with urllib.request.urlopen(f'http://{host}:{port}/log?token={token}&tail=400') as r:
        lines = r.read().decode('utf-8', 'replace').split('\n')
    for l in reversed(lines):
        m = re.search(r'^(\S+) .*glass telemetry from RIGHT.* flags=(0x[0-9a-f]+)', l)
        if m:
            return m.group(1), int(m.group(2), 16)
    return None


def say_state(host, port, token):
    got = newest_flags(host, port, token)
    if got is None:
        print('no telemetry line in the phone\'s log yet (are the glasses connected?)')
        return
    at, flags = got
    print(f'at {at} the right lens reported flags 0x{flags:x}: {FLAGS.get(flags, "another stage or still arming")}')


async def arm(host, port, token, stage):
    import websockets
    async with websockets.connect(f'ws://{host}:{port}/ws?token={token}', max_size=None) as ws:
        async def drain():
            try:
                async for _ in ws:
                    pass
            except Exception as e:           # the socket closing ends the reader; said, not swallowed
                print(f'[reader ended: {type(e).__name__}]')
        reader = asyncio.create_task(drain())
        for name, value, pause in (('c1', str(stage), 1.5), ('rest', 'on', 4.0), ('rest', 'off', 0.5)):
            await ws.send(json.dumps({'t': 'probe', 'name': name, 'value': value}))
            print(f'{time.strftime("%H:%M:%S")} sent {name}={value}')
            await asyncio.sleep(pause)
        reader.cancel()


def main(argv):
    if len(argv) != 1 or argv[0] not in ('state', '4', '5'):
        print(__doc__)
        return 64
    host, port, token = config()
    try:
        if argv[0] == 'state':
            say_state(host, port, token)
            return 0
        asyncio.run(arm(host, port, token, int(argv[0])))
        print('the session is starting again; reading the flags in 20 s …')
        time.sleep(20)
        say_state(host, port, token)
        return 0
    except Exception as e:
        # never the token: a URL in an error's text is cut out whole
        print('failed: ' + re.sub(r'token=[^&\s\']+', 'token=<token>', f'{type(e).__name__}: {e}'))
        return 1


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
