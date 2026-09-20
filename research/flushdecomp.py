#!/usr/bin/env python3
"""Image-lane messages in a BTSnoop capture of the APK driving: time from the host's queue to the
ack, and what the bodies would weigh under other compression shapes (`HANDOFF.md` §68).

Context: Damage draws on Adam's own G2 glasses over Bluetooth; every flush is an EvenHub image
message written to LEFT and acked by RIGHT. This reads our own captures to price two ideas from
upstream g2flash's private message transport: a compression context kept across messages, and
whole-message deflate (cache writes go uncompressed today). Offline, read-only, stdlib only.

    python3 research/flushdecomp.py                      # every captures/apk-*.log
    python3 research/flushdecomp.py captures/apk-20260914-1731-1756.log

Per capture: queue -> ack at HCI level by wire size (the phone's journal adds the Android stack on
top). Over all captures: bytes per top-level mode as sent, deflated per message with a fresh
context, and deflated through ONE context kept across messages (Z_SYNC_FLUSH per message) at
32 KiB / 4 KiB / 1 KiB windows; then the mode-8 flushes' size percentiles and AA packet counts.
"As sent" inflates nothing; the other columns first inflate each mode-3/6 zlib stream to its raw
RLE, which is what a transport-level compressor would be handed. The controller's
Number-of-Completed-Packets reports lag the ack by tens of ms here, so they time nothing.

Byte layouts: core/wire/AaFrame.kt (the AA envelope), core/wire/EvenHubMsg.kt (ImgRawMsg, the
ack), g2flash a5d1c31 patches/zlib_glue.c's header (modes 3, 6, 8).
"""
import collections, glob, os, struct, sys, zlib

ENVELOPE = 40          # ImgRawMsg's protobuf fields around the data, roughly (EvenHubMsg.imageFragment)
CHUNK = 232            # AA fragment payload (AaFrame.CHUNK)

def records(path):
    d = open(path, 'rb').read()
    if d[:8] != b'btsnoop\x00':
        return
    off = 16
    while off + 24 <= len(d):
        ol, il, flags, drops, ts = struct.unpack('>IIIIq', d[off:off + 24]); off += 24
        pkt = d[off:off + il]; off += il
        yield ts, flags, pkt

def varint(b, i):
    v = 0; sh = 0
    while True:
        c = b[i]; i += 1; v |= (c & 0x7F) << sh; sh += 7
        if not c & 0x80: return v, i

def pb_fields(b):
    i = 0; out = {}
    while i < len(b):
        key, i = varint(b, i)
        f, wt = key >> 3, key & 7
        if wt == 0:
            out[f], i = varint(b, i)
        elif wt == 2:
            n, i = varint(b, i); out[f] = bytes(b[i:i + n]); i += n
        else:
            raise ValueError('wire type %d' % wt)
    return out

def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(p * len(xs)))] if xs else 0

def att(path):
    """(outbound writes, inbound notifications) as (handle, ts, value), L2CAP reassembled."""
    l2 = {}; writes = []; notifs = []; out_bytes = collections.Counter()
    for ts, flags, pkt in records(path):
        if not pkt or pkt[0] != 0x02 or len(pkt) < 5: continue
        hf, ln = struct.unpack('<HH', pkt[1:5]); h = hf & 0xFFF; pb = (hf >> 12) & 3
        outbound = (flags & 1) == 0
        body = pkt[5:5 + ln]
        if outbound: out_bytes[h] += ln
        key = (h, outbound)
        if pb in (0, 2):
            if len(body) < 4: continue
            need, cid = struct.unpack('<HH', body[:4])
            l2[key] = [need, cid, bytearray(body[4:]), ts]
        elif key in l2:
            l2[key][2] += body
        else:
            continue
        need, cid, buf, t0 = l2[key]
        if len(buf) >= need:
            del l2[key]
            if cid == 4 and buf:
                if outbound and buf[0] in (0x52, 0x12): writes.append((h, t0, bytes(buf[3:])))
                elif not outbound and buf[0] == 0x1B: notifs.append((h, ts, bytes(buf[3:])))
    return writes, notifs, out_bytes

def aa_messages(items, kind):
    """Reassembled AA payloads: (handle, ts of first packet, ts of last, sid, payload)."""
    part = {}
    for h, ts, v in items:
        if len(v) < 8 or v[0] != 0xAA or v[1] != kind: continue
        seq, ln, tot, ser, sid = v[2], v[3], v[4], v[5], v[6]
        k = (h, seq)
        if ser == 1: part[k] = [ts, bytearray()]
        if k not in part: continue
        part[k][1] += v[8:8 + ln]
        if ser == tot:
            t0, buf = part.pop(k)
            yield h, t0, ts, sid, bytes(buf[:-2])

def analyse(path):
    writes, notifs, out_bytes = att(path)
    if not out_bytes: return None
    left = max(out_bytes, key=out_bytes.get)              # the arm that takes the bulk bytes
    msgs = []
    for h, t0, tlast, sid, payload in aa_messages(writes, 0x21):
        if h != left or sid != 0xE0: continue
        try:
            f = pb_fields(payload)
            if f.get(1) != 3: continue                    # Cmd 3 = image raw data
            img = pb_fields(f[5])
        except (ValueError, IndexError, KeyError):
            continue
        data = img.get(8, b'')
        if img.get(6) != 0 or img.get(4) != len(data): continue   # single-fragment flushes only
        msgs.append(dict(t0=t0, tlast=tlast, msgId=f.get(2), wire=len(payload), data=data, tack=None))
    acks = []
    for h, t0, tlast, sid, payload in aa_messages(notifs, 0x12):
        if sid != 0xE0: continue
        try: f = pb_fields(payload)
        except (ValueError, IndexError): continue
        if f.get(1) == 4: acks.append((tlast, f.get(2)))  # f1 = Cmd + 1, f2 echoes msgId
    for m in msgs:
        for ts, mid in acks:
            if ts >= m['tlast'] and mid == m['msgId'] and ts - m['tlast'] < 5_000_000:
                m['tack'] = ts; break
    return msgs

def expand(body):
    """The message with every mode-3/6 zlib stream inflated to raw RLE; None if not understood."""
    if not body: return None
    mode = body[0] & 0x7F; stereo = body[0] & 0x80
    try:
        if mode == 8:
            pos = 2; out = bytearray(body[:2])
            for _ in range(body[1]):
                ln = struct.unpack('<H', body[pos:pos + 2])[0]; pos += 2
                sub = expand(body[pos:pos + ln]); pos += ln
                if sub is None: return None
                out += struct.pack('<H', min(len(sub), 0xFFFF)) + sub
            return bytes(out)
        if mode == 3:
            hdr = 1 + (8 if stereo else 4) + 2            # boxes (two under the high bit), fid16
            return body[:hdr] + zlib.decompress(body[hdr:])
        if mode == 6:
            return body[:1] + zlib.decompress(body[1:])
        return body
    except (zlib.error, struct.error, IndexError):
        return None

def deflated(co, e):
    return len(co.compress(e)) + len(co.flush(zlib.Z_SYNC_FLUSH))

def packets(n):
    return max(1, -(-(n + ENVELOPE + 2) // CHUNK))

BANDS = [(0, CHUNK, '1 packet'), (CHUNK, 500, '<500 B'), (500, 1500, '0.5-1.5 KB'),
         (1500, 3000, '1.5-3 KB'), (3000, 1 << 30, '3 KB+')]

def main():
    paths = sys.argv[1:] or sorted(glob.glob(os.path.join(os.path.dirname(__file__), '..', 'captures', 'apk-*.log')))
    tot = collections.defaultdict(lambda: [0] * 6)        # mode -> n, sent, fresh, kept 15/12/10
    flushes = []                                          # mode-8: (sent, fresh, kept15, kept12)
    for path in paths:
        msgs = analyse(path)
        if not msgs: continue
        acked = [m for m in msgs if m['tack']]
        print('== %s: %d single-fragment image messages, %d acked' % (os.path.basename(path), len(msgs), len(acked)))
        print('   %-11s %5s   queue -> ack ms at HCI level (median / p75 / p90)' % ('wire size', 'n'))
        for lo, hi, name in BANDS:
            xs = [(m['tack'] - m['t0']) / 1000 for m in acked if lo <= m['wire'] < hi]
            if xs: print('   %-11s %5d   %6.1f / %6.1f / %6.1f' % (name, len(xs), pct(xs, .5), pct(xs, .75), pct(xs, .9)))
        kept = {w: zlib.compressobj(6, zlib.DEFLATED, w) for w in (15, 12, 10)}   # one context per session
        for m in msgs:
            b = m['data']; e = expand(b)
            if e is None: continue
            fresh = min(len(b), deflated(zlib.compressobj(6, zlib.DEFLATED, 15), e))
            k = {w: min(len(b), deflated(co, e)) for w, co in kept.items()}
            t = tot[b[0] & 0x7F]
            for i, v in enumerate((1, len(b), fresh, k[15], k[12], k[10])): t[i] += v
            if b[0] & 0x7F == 8: flushes.append((len(b), fresh, k[15], k[12]))
    if not tot: return
    print('\nbytes by top-level mode, all captures (% of sent)')
    print('mode     n     sent | fresh context |  kept, 32 KiB |   kept, 4 KiB |   kept, 1 KiB')
    for mode, t in sorted(tot.items()):
        print('%4d %5d %8d | ' % (mode, t[0], t[1]) + ' | '.join('%8d %3.0f%%' % (t[i], 100.0 * t[i] / t[1]) for i in (2, 3, 4, 5)))
    names = ('as sent', 'fresh context', 'kept 32 KiB', 'kept 4 KiB')
    print('\nmode-8 flushes, n=%d' % len(flushes))
    for i, name in enumerate(names):
        xs = [f[i] for f in flushes]
        print('   %-14s median / p75 / p90 %5d / %5d / %5d B   packets per flush %.2f   one-packet flushes %d'
              % (name, pct(xs, .5), pct(xs, .75), pct(xs, .9), sum(packets(x) for x in xs) / len(xs),
                 sum(1 for x in xs if packets(x) == 1)))

if __name__ == '__main__':
    main()
