"""Build a field-name registry from Even's own FileDescriptorProtos.

The vendor .proto definitions ship base64-encoded inside g2-kit's generated TS
(`ble/gen/*_pb.ts`, `fileDesc("...")`). This parses them with a plain varint walker —
no protobuf runtime needed — into {message_name: {field_no: (name, type, type_name)}}.
Note the base64 ships UNPADDED.
"""
import base64, re, pathlib

GEN = pathlib.Path("/home/user/damagewm/reference/g2-kit-unofficial/ble/gen")
TYPES = {1:'double',2:'float',3:'int64',4:'uint64',5:'int32',6:'fixed64',7:'fixed32',
         8:'bool',9:'string',10:'group',11:'message',12:'bytes',13:'uint32',14:'enum',
         15:'sfixed32',16:'sfixed64',17:'sint32',18:'sint64'}

def _rv(b, i):
    v = s = 0
    while True:
        c = b[i]; i += 1; v |= (c & 0x7f) << s
        if not c & 0x80: return v, i
        s += 7

def _fields(b):
    i = 0
    while i < len(b):
        try: k, i = _rv(b, i)
        except IndexError: return
        t, w = k >> 3, k & 7
        if w == 0: v, i = _rv(b, i); yield t, v
        elif w == 2:
            l, i = _rv(b, i); yield t, b[i:i+l]; i += l
        elif w == 5: yield t, b[i:i+4]; i += 4
        elif w == 1: yield t, b[i:i+8]; i += 8
        else: return

def _msg(raw, msgs, enums):
    name = None; flds = {}; nested = []; enums_in = []
    for t, v in _fields(raw):
        if t == 1 and isinstance(v, bytes): name = v.decode('utf8', 'replace')
        elif t == 2 and isinstance(v, bytes):
            fn = num = typ = lbl = tn = None
            for a, b2 in _fields(v):
                if a == 1 and isinstance(b2, bytes): fn = b2.decode('utf8', 'replace')
                elif a == 3: num = b2
                elif a == 4: lbl = b2
                elif a == 5: typ = b2
                elif a == 6 and isinstance(b2, bytes): tn = b2.decode('utf8', 'replace')
            if num is not None:
                flds[num] = (fn, TYPES.get(typ, '?'), (tn or '').split('.')[-1], lbl == 3)
        elif t == 3 and isinstance(v, bytes): nested.append(v)
        elif t == 4 and isinstance(v, bytes): enums_in.append(v)
    if name: msgs[name] = flds
    for n in nested: _msg(n, msgs, enums)
    for e in enums_in: _enum(e, enums)

def _enum(raw, enums):
    name = None; vals = {}
    for t, v in _fields(raw):
        if t == 1 and isinstance(v, bytes): name = v.decode('utf8', 'replace')
        elif t == 2 and isinstance(v, bytes):
            en = None; num = 0
            for a, b2 in _fields(v):
                if a == 1 and isinstance(b2, bytes): en = b2.decode('utf8', 'replace')
                elif a == 2: num = b2
            vals[num] = en
    if name: enums[name] = vals

def load():
    msgs, enums = {}, {}
    for f in sorted(GEN.glob("*_pb.ts")):
        m = re.search(r'fileDesc\("([A-Za-z0-9+/=]+)"', f.read_text())
        if not m: continue
        s = m.group(1); s += "=" * (-len(s) % 4)
        raw = base64.b64decode(s)
        for t, v in _fields(raw):
            if t == 4 and isinstance(v, bytes): _msg(v, msgs, enums)
            elif t == 5 and isinstance(v, bytes): _enum(v, enums)
    return msgs, enums

# sid -> root message, from service_id_def.proto's SID enum cross-referenced to each file
SID_ROOT = {
    0x01: ("DashboardDataPackage",   "DASHBOARD"),
    0x03: ("meun_main_msg_ctx",      "MENU"),
    0x04: ("NotificationDataPackage","NOTIFICATION"),
    0x06: ("TelepromptDataPackage",  "TELEPROMPT"),
    0x07: ("EvenAIDataPackage",      "EVEN_AI"),
    0x08: ("navigation_main_msg_ctx","NAVIGATION"),
    0x09: ("G2SettingPackage",       "SETTING"),
    0x0c: ("QuicklistDataPackage",   "QUICKLIST"),
    0x0d: ("sync_info_main_msg_ctx", "SYNC_INFO"),
    0x0e: ("HealthDataPackage",      "HEALTH"),
    0x0f: ("logger_main_msg_ctx",    "LOGGER"),
    0x10: ("OnboardingDataPackage",  "ONBOARDING"),
    0x20: ("module_configure_main_msg_ctx", "MODULE_CONFIGURE"),
    0x80: ("DevCfgDataPackage",      "DEVICE_SETTINGS (dev_config)"),
    0x81: ("GlassesCaseDataPackage", "GLASSES_CASE"),
    0x05: ("TranslateDataPackage",   "TRANSLATE"),
    0x0a: ("TranscribeDataPackage",  "TRANSCRIBE"),
    0x0b: ("ConversateDataPackage",  "CONVERSATE"),
    0x90: ("RingDataPackage",        "RING_RAW_DATA"),
    0x91: ("RingDataPackage",        "RING_DATA_RELAY"),
    0xe0: ("evenhub_main_msg_ctx",   "EVENHUB"),
}
if __name__ == "__main__":
    m, e = load()
    print(f"{len(m)} messages, {len(e)} enums from {len(list(GEN.glob('*_pb.ts')))} descriptors")
    print("sample:", list(m)[:6])
