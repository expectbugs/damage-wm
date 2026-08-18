"""Schema-validated decode of our BTSnoop captures.

BTSnoop -> HCI -> ATT -> AA envelope reassembly -> protobuf, annotated with Even's own
field names (research/decode/schema.py).

AA envelope (G2_BLE_PROTOCOL.md §2, confirmed against g2-kit's envelope.ts):
  [0]=0xAA  [1]=type(0x21 cmd P->G / 0x12 rsp G->P)  [2]=seq  [3]=len
  [4]=pktTot [5]=pktSer(1-indexed) [6]=sid [7]=flag  [8..]=payload  [+CRC16 LE on final]
`len` = chunk length; on the FINAL fragment it includes the 2 CRC bytes.
"""
import struct, sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).parent))
from schema import load, SID_ROOT

MSGS, ENUMS = load()
CAP = pathlib.Path("/home/user/damagewm/captures")

def records(path):
    b = pathlib.Path(path).read_bytes(); i = 16
    while i + 24 <= len(b):
        o, il, fl, dr, ts = struct.unpack(">IIIIq", b[i:i+24])
        yield ts, fl, b[i+24:i+24+il]; i += 24 + il

def rv(b, i):
    v = s = 0
    while True:
        c = b[i]; i += 1; v |= (c & 0x7f) << s
        if not c & 0x80: return v, i
        s += 7

def pb(buf, msg=None, depth=0, maxd=6):
    """Decode protobuf into annotated lines using the vendor schema when known."""
    out = []; i = 0; fields = MSGS.get(msg, {}) if msg else {}
    pad = "  " * depth
    while i < len(buf):
        try: k, i = rv(buf, i)
        except IndexError: break
        t, w = k >> 3, k & 7
        meta = fields.get(t)
        nm = f"{meta[0]}" if meta else f"f{t}"
        try:
            if w == 0:
                v, i = rv(buf, i)
                ev = ""
                if meta and meta[2] in ENUMS:
                    ev = f"  = {ENUMS[meta[2]].get(v, '?')}"
                out.append(f"{pad}{nm} = {v}{ev}")
            elif w == 2:
                l, i = rv(buf, i); sub = buf[i:i+l]; i += l
                if meta and meta[1] == 'string':
                    out.append(f'{pad}{nm} = "{sub.decode("utf8","replace")}"')
                elif meta and meta[1] == 'bytes':
                    out.append(f"{pad}{nm} = <{len(sub)} bytes> {sub[:12].hex()}...")
                elif meta and meta[1] == 'message' and depth < maxd:
                    out.append(f"{pad}{nm} {{")
                    out += pb(sub, meta[2], depth+1, maxd)
                    out.append(f"{pad}}}")
                elif depth < maxd and sub and _looks_pb(sub):
                    out.append(f"{pad}{nm} {{")
                    out += pb(sub, meta[2] if meta else None, depth+1, maxd)
                    out.append(f"{pad}}}")
                else:
                    try: out.append(f'{pad}{nm} = "{sub.decode("utf8")}"')
                    except Exception: out.append(f"{pad}{nm} = <{len(sub)}B> {sub[:16].hex()}")
            elif w == 5: out.append(f"{pad}{nm} = <fixed32> {buf[i:i+4].hex()}"); i += 4
            elif w == 1: out.append(f"{pad}{nm} = <fixed64> {buf[i:i+8].hex()}"); i += 8
            else: break
        except IndexError: break
    return out

def _looks_pb(b):
    try:
        i = 0; n = 0
        while i < len(b) and n < 3:
            k, i = rv(b, i); w = k & 7
            if w == 0: _, i = rv(b, i)
            elif w == 2:
                l, i = rv(b, i); i += l
            elif w == 5: i += 4
            elif w == 1: i += 8
            else: return False
            n += 1
        return i <= len(b)
    except Exception: return False

def messages(path):
    """Yield (ts, dir, sid, flag, payload) with AA fragments reassembled by seq."""
    asm = {}
    for ts, fl, p in records(path):
        if not p or p[0] != 0x02 or len(p) < 12: continue
        h = struct.unpack("<H", p[1:3])[0] & 0xFFF
        l2, cid = struct.unpack("<HH", p[5:9])
        if cid != 4: continue
        op = p[9]
        if op in (0x52, 0x12): body = p[12:]; d = "P->G"
        elif op == 0x1B:       body = p[12:]; d = "G->P"
        else: continue
        while body:
            if len(body) < 8 or body[0] != 0xAA: break
            typ, seq, ln, tot, ser, sid, flag = body[1], body[2], body[3], body[4], body[5], body[6], body[7]
            frag = body[8:8+ln]
            key = (h, seq, sid)
            if ser == 1: asm[key] = bytearray()
            if key in asm:
                asm[key] += frag
                if ser == tot:
                    full = bytes(asm.pop(key))
                    yield ts, d, h, sid, flag, full[:-2] if len(full) >= 2 else full
            body = body[8+ln:]
