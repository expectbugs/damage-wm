#!/usr/bin/env python3
"""Update an Even Realities R1 ring over Nordic Secure DFU, from Linux + bleak.

Our own implementation of the standard Nordic Secure DFU state machine. The
R1-specific parts -- the buttonless entry characteristic, PRN = 12, the 400 ms
settle after the first data object, the bounded object rewrite on a CRC
mismatch -- are protocol FACTS read out of SybilSight-webflasher's
`src/lib/r1Dfu.js` (MIT). No code was copied; the wire behaviour is the same
because the protocol is.

Stages, chosen with --stage:

  verify  offline only. Check the package against its pinned hashes. No radio.
  probe   the above, then connect to the ring, confirm the DFU service and the
          buttonless characteristic are present, read the name, disconnect.
          WRITES NOTHING.
  flash   the above, then buttonless entry and the full DFU. This writes
          firmware.

On the "no timeouts" rule: a DFU cannot wait forever for a response that a
dropped packet means will never arrive, and the reference's own retry logic is
built on bounded waits. Every wait here that can expire raises LOUDLY and stops
the run -- nothing is retried silently and nothing is abandoned quietly. The
only automatic retry is the protocol's own bounded object rewrite, which is a
correctness mechanism (CREATE resets the device's write pointer and the same
bytes are replayed), not a timer.
"""
import argparse, asyncio, hashlib, json, pathlib, sys, zlib

from bleak import BleakClient, BleakScanner

DFU_SERVICE = "0000fe59-0000-1000-8000-00805f9b34fb"
CTRL_UUID = "8ec90001-f315-4f60-9fb8-838830daea50"
PACKET_UUID = "8ec90002-f315-4f60-9fb8-838830daea50"
BUTTONLESS_UUID = "8ec90003-f315-4f60-9fb8-838830daea50"

RESPONSE, SUCCESS = 0x60, 0x01
OP_CREATE, OP_SET_PRN, OP_CALC_CRC, OP_EXECUTE, OP_SELECT = 0x01, 0x02, 0x03, 0x04, 0x06
OBJ_COMMAND, OBJ_DATA = 0x01, 0x02

CHUNK = 20                  # ATT minimum payload; the reference does not raise MTU
PRN_INTERVAL = 12           # packet-receipt every 12 chunks, for the data object only
FIRST_DATA_SETTLE = 0.40    # SDK 15/16 bootloaders drop early packets without this
RETRY_SETTLE = 0.15         # let a stale receipt land before re-CREATEing
OBJECT_ATTEMPTS = 3
WAIT_SECONDS = 15.0         # a wait that expires is fatal and loud, never silent

RESULT = {
    0x00: "invalid code", 0x01: "success", 0x02: "opcode not supported",
    0x03: "invalid parameter", 0x04: "insufficient resources", 0x05: "invalid object",
    0x07: "unsupported object type", 0x08: "operation not permitted",
    0x0A: "operation failed", 0x0B: "extended error",
}


class DfuError(RuntimeError):
    pass


# ---------------------------------------------------------------- the package
def load_package(dirpath):
    """Verify a pinned R1 release on disk and return (init_packet, application)."""
    d = pathlib.Path(dirpath)
    meta = json.loads((d / "metadata.json").read_text())
    app_meta = meta["application"]
    zip_path = d / meta["sourceFile"]

    def sha(p):
        return hashlib.sha256(p.read_bytes()).hexdigest()

    checks = [
        ("archive sha256", sha(zip_path), meta["sourceSha256"]),
        ("archive size", zip_path.stat().st_size, meta["sourceSize"]),
        ("application.bin sha256", sha(d / "application.bin"), app_meta["binSha256"]),
        ("application.bin size", (d / "application.bin").stat().st_size, app_meta["binSize"]),
        ("application.dat sha256", sha(d / "application.dat"), app_meta["datSha256"]),
        ("application.dat size", (d / "application.dat").stat().st_size, app_meta["datSize"]),
    ]
    bad = [(w, g, e) for w, g, e in checks if g != e]
    for what, got, want in checks:
        print(f"  {'FAIL' if (what, got, want) in bad else 'ok  '}  {what}")
        if (what, got, want) in bad:
            print(f"          got  {got}\n          want {want}")
    if bad:
        raise DfuError(f"{len(bad)} package check(s) failed -- refusing to flash")

    # The .dat is Nordic's SIGNED init packet. It is opaque and must ship verbatim;
    # the bootloader enforces the signature, so a modified one is simply rejected.
    init = (d / "application.dat").read_bytes()
    app = (d / "application.bin").read_bytes()
    print(f"  package ok: R1 {meta['version']} ({meta['format']}, {meta['trust']})")
    print(f"              init packet {len(init)} B, application {len(app):,} B")
    return meta, init, app


# ------------------------------------------------------------------- the link
class Dfu:
    def __init__(self, client, verbose=False):
        self.c = client
        self.q = asyncio.Queue()
        self.verbose = verbose

    def _on_notify(self, _char, data):
        self.q.put_nowait(bytes(data))

    async def start(self):
        await self.c.start_notify(CTRL_UUID, self._on_notify)

    async def _recv(self, expect_op, what):
        """One control-point notification. Command responses and packet receipts
        share this queue; writes and commands are strictly serialized so ordering
        is unambiguous."""
        try:
            frame = await asyncio.wait_for(self.q.get(), WAIT_SECONDS)
        except asyncio.TimeoutError:
            raise DfuError(
                f"no reply to {what} within {WAIT_SECONDS:g}s. The ring stopped "
                f"answering on the control point; nothing further was written."
            ) from None
        if len(frame) < 3:
            raise DfuError(f"{what}: runt reply {frame.hex()}")
        if frame[0] != RESPONSE:
            raise DfuError(f"{what}: reply does not start 0x60: {frame.hex()}")
        if frame[1] != expect_op:
            raise DfuError(f"{what}: reply is for op 0x{frame[1]:02x}, expected "
                           f"0x{expect_op:02x}: {frame.hex()}")
        if frame[2] != SUCCESS:
            raise DfuError(f"{what}: rejected, status 0x{frame[2]:02x} "
                           f"({RESULT.get(frame[2], 'unknown')}): {frame.hex()}")
        return frame[3:]

    async def _command(self, payload, what):
        await self.c.write_gatt_char(CTRL_UUID, bytes(payload), response=True)
        return await self._recv(payload[0], what)

    async def select(self, obj_type):
        body = await self._command([OP_SELECT, obj_type], f"SELECT type {obj_type}")
        if len(body) < 12:
            raise DfuError(f"SELECT reply carries {len(body)} B, expected 12")
        max_size, offset, crc = (int.from_bytes(body[i:i + 4], "little") for i in (0, 4, 8))
        return max_size, offset, crc

    async def create(self, obj_type, size):
        await self._command(
            [OP_CREATE, obj_type] + list(size.to_bytes(4, "little")),
            f"CREATE type {obj_type} size {size}")

    async def set_prn(self, interval):
        await self._command(
            [OP_SET_PRN] + list(interval.to_bytes(2, "little")), f"SET_PRN {interval}")

    async def execute(self):
        await self._command([OP_EXECUTE], "EXECUTE")

    async def calc_crc(self):
        body = await self._command([OP_CALC_CRC], "CALCULATE_CRC")
        if len(body) < 8:
            raise DfuError(f"CRC reply carries {len(body)} B, expected 8")
        return int.from_bytes(body[0:4], "little"), int.from_bytes(body[4:8], "little")

    async def write_object(self, source, start, end, prn):
        """Write source[start:end] to the packet characteristic in 20 B chunks,
        checking every packet receipt against the running CRC."""
        sent = 0
        since_receipt = 0
        for pos in range(start, end, CHUNK):
            piece = source[pos:min(pos + CHUNK, end)]
            await self.c.write_gatt_char(PACKET_UUID, piece, response=False)
            sent += len(piece)
            since_receipt += 1
            if prn and since_receipt == prn:
                since_receipt = 0
                await self._check_receipt(source, start + sent)
        if prn and since_receipt:
            pass  # a partial group gets no receipt; the object CRC covers it

    async def _check_receipt(self, source, absolute_end):
        body = await self._recv(OP_CALC_CRC, "packet receipt")
        if len(body) < 8:
            raise DfuError(f"packet receipt carries {len(body)} B, expected 8")
        offset = int.from_bytes(body[0:4], "little")
        crc = int.from_bytes(body[4:8], "little")
        if offset != absolute_end:
            raise DfuError(f"packet receipt reports offset {offset}, expected "
                           f"{absolute_end} -- bytes were lost in flight")
        want = zlib.crc32(source[:absolute_end]) & 0xFFFFFFFF
        if crc != want:
            raise DfuError(f"packet receipt CRC 0x{crc:08x} != 0x{want:08x} at byte {offset}")

    async def transfer_init(self, init):
        max_size, offset, _crc = await self.select(OBJ_COMMAND)
        if max_size < len(init):
            raise DfuError(f"command object max {max_size} B < init packet {len(init)} B")
        if offset:
            raise DfuError(f"a partial command object is already present (offset {offset}); "
                           f"re-enter DFU mode and retry so this starts clean")
        await self.create(OBJ_COMMAND, len(init))
        await self.write_object(init, 0, len(init), prn=0)
        off, crc = await self.calc_crc()
        want = zlib.crc32(init) & 0xFFFFFFFF
        if off != len(init) or crc != want:
            raise DfuError(f"init packet CRC mismatch: device says offset {off} "
                           f"crc 0x{crc:08x}, expected {len(init)} / 0x{want:08x}")
        await self.execute()
        print("  init packet accepted (signature checked by the bootloader)")

    async def transfer_app(self, app, on_progress):
        max_size, offset, _crc = await self.select(OBJ_DATA)
        if not max_size:
            raise DfuError("device reports a data-object max size of 0")
        if offset:
            raise DfuError(f"a partial data object is already present (offset {offset}); "
                           f"re-enter DFU mode and retry so this starts clean")
        print(f"  data object window {max_size} B, {len(app):,} B to send")
        pos = 0
        while pos < len(app):
            end = min(pos + max_size, len(app))
            for attempt in range(1, OBJECT_ATTEMPTS + 1):
                try:
                    await self.create(OBJ_DATA, end - pos)
                    if pos == 0:
                        # Without this the first packets of the first object can be
                        # discarded by SDK 15/16 bootloaders.
                        await asyncio.sleep(FIRST_DATA_SETTLE)
                    await self.write_object(app, pos, end, prn=PRN_INTERVAL)
                    off, crc = await self.calc_crc()
                    want = zlib.crc32(app[:end]) & 0xFFFFFFFF
                    if off != end or crc != want:
                        raise DfuError(f"object CRC mismatch: device says offset {off} "
                                       f"crc 0x{crc:08x}, expected {end} / 0x{want:08x}")
                    break
                except DfuError as e:
                    if attempt == OBJECT_ATTEMPTS:
                        raise DfuError(f"object [{pos},{end}) failed {OBJECT_ATTEMPTS} "
                                       f"times: {e}") from None
                    print(f"     retry {attempt}/{OBJECT_ATTEMPTS - 1} on [{pos},{end}): {e}")
                    # Drain anything stale, then let the radio settle: a late receipt
                    # arriving during the retry's CREATE would poison it.
                    while not self.q.empty():
                        self.q.get_nowait()
                    await asyncio.sleep(RETRY_SETTLE)
            await self.execute()
            pos = end
            on_progress(pos, len(app))


# ------------------------------------------------------------------- the flow
async def find(predicate, what):
    found = await BleakScanner.discover(timeout=20, return_adv=True)
    hits = [(a, d, adv) for a, (d, adv) in found.items() if predicate(a, d, adv)]
    if len(hits) != 1:
        names = [f"{a} {(adv.local_name or d.name or '?')}" for a, d, adv in hits]
        raise DfuError(f"expected exactly one {what}, found {len(hits)}: {names}")
    addr, dev, adv = hits[0]
    print(f"  found {what}: {addr}  '{adv.local_name or dev.name}'  rssi {adv.rssi} dBm")
    return addr


async def run(args):
    print("== 1. package ==")
    meta, init, app = load_package(args.package)
    if args.stage == "verify":
        print("\nverify only; the radio was not touched.")
        return 0

    print("\n== 2. the ring, as an application ==")
    app_addr = args.address
    if not app_addr:
        app_addr = await find(
            lambda a, d, adv: (adv.local_name or d.name or "").startswith("EVEN R1_"),
            "R1 ring")

    # bleak 3.x has no set_disconnected_callback -- it is a constructor argument,
    # so the callback has to be wired before connecting. The ring reboots into its
    # bootloader when it accepts the enter byte, so the link ENDING is the success
    # signal and we must not miss it.
    dropped = asyncio.Event()
    loop = asyncio.get_running_loop()
    c = BleakClient(app_addr,
                    disconnected_callback=lambda _: loop.call_soon_threadsafe(dropped.set))
    await c.connect()
    entered = False
    try:
        svcs = {s.uuid.lower() for s in c.services}
        if DFU_SERVICE not in svcs:
            raise DfuError(f"{app_addr} does not advertise the Nordic DFU service "
                           f"{DFU_SERVICE}. Services: {sorted(svcs)}")
        chars = {ch.uuid.lower() for s in c.services for ch in s.characteristics}
        if BUTTONLESS_UUID not in chars:
            raise DfuError(f"no buttonless DFU characteristic {BUTTONLESS_UUID}")
        print("  DFU service present; buttonless characteristic present")
        if args.stage == "probe":
            print("\nprobe only; nothing was written.")
            return 0

        print("\n== 3. entering DFU mode ==")
        await c.start_notify(BUTTONLESS_UUID, lambda _ch, _d: None)
        await c.write_gatt_char(BUTTONLESS_UUID, bytes([0x01]), response=True)
        try:
            await asyncio.wait_for(dropped.wait(), WAIT_SECONDS)
        except asyncio.TimeoutError:
            raise DfuError("the ring did not restart into DFU mode; it is still "
                           "running its application and nothing was written") from None
        entered = True
        print("  the ring dropped the link and is restarting into its bootloader")
    finally:
        try:
            await c.disconnect()
        except Exception as e:                       # noqa: BLE001 - report, never swallow
            # Expected once the ring has already gone; say so rather than hide it.
            print(f"  (closing the application link: {type(e).__name__}: {e})")
    if not entered:
        return 1

    await asyncio.sleep(2.0)

    print("\n== 4. the bootloader ==")
    boot_addr = await find(
        lambda a, d, adv: "DFU" in (adv.local_name or d.name or "").upper()
        and DFU_SERVICE in [u.lower() for u in (adv.service_uuids or [])],
        "R1 bootloader")

    print("\n== 5. the transfer ==")
    async with BleakClient(boot_addr) as c:
        dfu = Dfu(c)
        await dfu.start()
        await dfu.set_prn(0)
        await dfu.transfer_init(init)
        await dfu.set_prn(PRN_INTERVAL)

        state = {"pct": -1}

        def progress(done, total):
            pct = done * 100 // total
            if pct // 5 != state["pct"] // 5:
                state["pct"] = pct
                print(f"     {done:>7,} / {total:,} B  ({pct}%)")

        await dfu.transfer_app(app, progress)

    print(f"\n=== R1 updated to {meta['version']}; the ring is restarting ===")
    return 0


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--package", required=True, help="directory holding metadata.json etc")
    p.add_argument("--stage", choices=("verify", "probe", "flash"), default="verify")
    p.add_argument("--address", help="the ring's BLE address (skips the app-device scan)")
    args = p.parse_args()
    try:
        return asyncio.run(run(args))
    except DfuError as e:
        print(f"\n!!! {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
