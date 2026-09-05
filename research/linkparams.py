#!/usr/bin/env python3
"""Link-layer parameters from the BTSnoop captures, per connection.

Walks each btsnoop file in captures/ and prints, for every LE connection it
sees, the peer address, the handle, the initial interval, every connection
update, the data-length (DLE) negotiation and any PHY update — the numbers
`overview.md` §5.1 reasons about. Offline, read-only, no dependencies.

Added 2026-09-05 (`HANDOFF.md` §32): a first parse found LE Connection Update
Complete events for handle 65 in `allbutimages.log`, which `CLAIMS.md` row 44
says never happen; this script attributes every handle to its peer address so
the claim can be checked rather than argued.

HCI event layout (Core spec vol 4 part E §7.7.65): packet[0]=0x04 (event),
[1]=0x3E (LE meta), [2]=len, [3]=subevent, then the subevent body.
"""
import glob, os, struct, sys

def packets(path):
    """(timestamp, packet bytes) for every record; packet[0] is the H4 type."""
    d = open(path, 'rb').read()
    if d[:8] != b'btsnoop\x00':
        return
    off = 16
    while off + 24 <= len(d):
        ol, il, flags, drops, ts = struct.unpack('>IIIIq', d[off:off + 24]); off += 24
        pkt = d[off:off + il]; off += il
        yield ts, pkt

def events(path):
    for ts, pkt in packets(path):
        if len(pkt) >= 4 and pkt[0] == 0x04:
            yield ts, pkt[1], pkt[3:]

def who_asked(path):
    """Which side asked for each interval change: the HOST's own
    LE_Connection_Update command (opcode 0x2013) per handle, and the
    PERIPHERAL's L2CAP Connection Parameter Update Request (signalling channel
    0x0005, code 0x12) per handle — the distinction `overview.md` §5.1 needs."""
    host, periph = {}, {}
    for ts, pkt in packets(path):
        if pkt[0] == 0x01 and len(pkt) >= 6 and struct.unpack('<H', pkt[1:3])[0] == 0x2013:
            h = struct.unpack('<H', pkt[4:6])[0] & 0x0FFF
            imin, imax = struct.unpack('<HH', pkt[6:10])
            host.setdefault(h, []).append('%.2f–%.2f ms' % (imin * 1.25, imax * 1.25))
        elif pkt[0] == 0x02 and len(pkt) >= 9:
            h = struct.unpack('<H', pkt[1:3])[0] & 0x0FFF
            cid = struct.unpack('<H', pkt[7:9])[0]
            if cid == 0x0005 and len(pkt) >= 18 and pkt[9] == 0x12:
                imin, imax, lat, sup = struct.unpack('<HHHH', pkt[13:21]) if len(pkt) >= 21 else (0, 0, 0, 0)
                periph.setdefault(h, []).append('%.2f–%.2f ms/lat %d' % (imin * 1.25, imax * 1.25, lat))
    return host, periph

def addr(b):
    return ':'.join('%02X' % x for x in reversed(b))

def run(path):
    print(os.path.basename(path))
    conns = {}          # handle -> dict
    order = []
    host, periph = who_asked(path)
    for ts, code, e in events(path):
        if code == 0x3E:
            sub = e[0]; b = e[1:]
            if sub == 0x01 and len(b) >= 18:        # LE Connection Complete
                h = struct.unpack('<H', b[1:3])[0]
                iv, lat, sup = struct.unpack('<HHH', b[11:17])
                conns[h] = {'addr': addr(b[5:11]), 'role': b[3], 'iv': [iv * 1.25], 'lat': [lat], 'sup': [sup * 10], 'dle': [], 'phy': []}
                order.append(h)
            elif sub == 0x0A and len(b) >= 30:      # LE Enhanced Connection Complete
                h = struct.unpack('<H', b[1:3])[0]
                iv, lat, sup = struct.unpack('<HHH', b[23:29])
                conns[h] = {'addr': addr(b[5:11]), 'role': b[3], 'iv': [iv * 1.25], 'lat': [lat], 'sup': [sup * 10], 'dle': [], 'phy': []}
                order.append(h)
            elif sub == 0x03 and len(b) >= 9:       # LE Connection Update Complete
                h = struct.unpack('<H', b[1:3])[0]
                iv, lat, sup = struct.unpack('<HHH', b[3:9])
                c = conns.setdefault(h, {'addr': '?', 'role': -1, 'iv': [], 'lat': [], 'sup': [], 'dle': [], 'phy': []})
                c['iv'].append(iv * 1.25); c['lat'].append(lat); c['sup'].append(sup * 10)
            elif sub == 0x07 and len(b) >= 10:      # LE Data Length Change
                h = struct.unpack('<H', b[0:2])[0]
                tx, txt, rx, rxt = struct.unpack('<HHHH', b[2:10])
                conns.setdefault(h, {'addr': '?', 'role': -1, 'iv': [], 'lat': [], 'sup': [], 'dle': [], 'phy': []})['dle'].append((tx, rx))
            elif sub == 0x0C and len(b) >= 5:       # LE PHY Update Complete
                h = struct.unpack('<H', b[1:3])[0]
                conns.setdefault(h, {'addr': '?', 'role': -1, 'iv': [], 'lat': [], 'sup': [], 'dle': [], 'phy': []})['phy'].append((b[3], b[4]))
        elif code == 0x05 and len(e) >= 4:           # Disconnection Complete
            h = struct.unpack('<H', e[1:3])[0]
            if h in conns: conns[h]['ended'] = 'reason 0x%02x' % e[3]
    for h in sorted(conns):
        c = conns[h]
        ivs = ' → '.join('%.2f ms/lat %d/sup %d ms' % (i, l, s) for i, l, s in zip(c['iv'], c['lat'], c['sup']))
        print('  handle %d  peer %s  role %s' % (h, c['addr'], {0: 'central', 1: 'peripheral'}.get(c['role'], '?')))
        print('    interval history: %s' % (ivs or '(connect not in the window)'))
        if c['dle']: print('    DLE: ' + ', '.join('tx %d/rx %d' % x for x in c['dle']))
        if c['phy']: print('    PHY updates: ' + ', '.join('tx %d rx %d' % x for x in c['phy']))
        if 'ended' in c: print('    disconnected: ' + c['ended'])
        if h in host: print('    host commanded LE_Connection_Update: ' + ', '.join(host[h]))
        if h in periph: print('    peripheral asked (L2CAP 0x12): ' + ', '.join(periph[h]))

if __name__ == '__main__':
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'captures')
    files = sys.argv[1:] or sorted(glob.glob(os.path.join(root, '*.log')))
    for f in files:
        run(f)
