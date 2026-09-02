#!/usr/bin/env python3
"""End-to-end check for audio/viz.py — no pytest, no network, no database.

    cd <repo>/audio
    /home/user/G2CC/audio/venv/bin/python tests/test_viz.py

It synthesizes its own audio with ffmpeg in a temp directory, runs viz.py the
way the Kotlin `Enrich` ingester runs it (`-m viz <file>`, blob on stdout),
and decodes the result with the small reader below — written from the DVIZ
layout in `core/.../windows/music/MusicModel.kt` (VizData), independently of
viz.py's own encoder, so the two have to agree on the bytes.

Everything lands in a disposable temp dir and is removed at the end.
"""

from __future__ import annotations

import math
import os
import shutil
import struct
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
AUDIO_DIR = os.path.dirname(HERE)

FPS = 20
BANDS = 24
DURATION_S = 6

_failures: list[str] = []


def check(cond: bool, what: str) -> None:
    if cond:
        print(f"  ok   {what}")
    else:
        print(f"  FAIL {what}")
        _failures.append(what)


# --------------------------------------------------- an independent reader

def decode_dviz(b: bytes) -> dict:
    """Mirror of VizData.decode — magic, u8 version/fps/bands, u32 LE frames /
    rmsCount / beats, packed nibbles high-first, then the beat times."""
    if len(b) < 19 or b[:4] != b"DVIZ":
        raise ValueError(f"not a DVIZ blob ({len(b)} B, magic {b[:4]!r})")
    version, fps, bands = b[4], b[5], b[6]
    if version != 1:
        raise ValueError(f"DVIZ version {version} unsupported")
    frames, rms_count, beats = struct.unpack_from("<III", b, 7)
    at = 19
    f_bytes = (frames * bands + 1) // 2
    r_bytes = (rms_count + 1) // 2
    need = at + f_bytes + r_bytes + beats * 4
    if len(b) < need:
        raise ValueError(f"DVIZ blob short: {len(b)} B, need {need} B")
    f_raw = b[at:at + f_bytes]; at += f_bytes
    r_raw = b[at:at + r_bytes]; at += r_bytes
    beat_ms = list(struct.unpack_from(f"<{beats}I", b, at)) if beats else []
    at += beats * 4

    def nib(buf: bytes, i: int) -> int:
        byte = buf[i >> 1]
        return byte >> 4 if i % 2 == 0 else byte & 0x0F

    grid = [[nib(f_raw, f * bands + k) for k in range(bands)] for f in range(frames)]
    rms = [nib(r_raw, i) for i in range(rms_count)]
    return {"version": version, "fps": fps, "bands": bands, "frames": frames,
            "grid": grid, "rms": rms, "beats": beat_ms, "trailing": len(b) - at}


# ------------------------------------------------------------------- fixture

def make_tone(path: str, freq: int, seconds: int) -> None:
    res = subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-f", "lavfi",
         "-i", f"sine=frequency={freq}:duration={seconds}", "-ac", "1", path],
        capture_output=True, text=True)
    if res.returncode != 0:
        raise RuntimeError(f"ffmpeg rc={res.returncode}: {res.stderr.strip()[:300]}")


def run_viz(audio: str, extra: list[str] | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, "-m", "viz", *(extra or []), audio],
        cwd=AUDIO_DIR, capture_output=True)


# --------------------------------------------------------------------- tests

def main() -> int:
    work = tempfile.mkdtemp(prefix="damage-viz-test-")
    try:
        wav = os.path.join(work, "test.wav")
        print(f"[fixture] {DURATION_S} s 440 Hz sine → {wav}")
        make_tone(wav, 440, DURATION_S)

        print("[case] -m viz writes a DVIZ blob to stdout")
        proc = run_viz(wav)
        check(proc.returncode == 0, f"exit 0 (got {proc.returncode}: "
                                    f"{proc.stderr.decode(errors='replace')[-300:]})")
        if proc.returncode != 0:
            return 1
        blob = proc.stdout
        check(len(blob) > 19, f"blob is {len(blob)} B")

        v = decode_dviz(blob)
        check(v["trailing"] == 0, f"no trailing bytes ({v['trailing']})")
        check(v["version"] == 1, "version 1")
        check(v["fps"] == FPS, f"fps {v['fps']} == {FPS}")
        check(v["bands"] == BANDS, f"bands {v['bands']} == {BANDS}")

        want_frames = math.ceil(DURATION_S * FPS)
        check(abs(v["frames"] - want_frames) <= 1,
              f"frames {v['frames']} ≈ {want_frames} ({DURATION_S} s × {FPS} fps)")
        want_rms = math.ceil(DURATION_S * 1000 / 20)
        check(abs(len(v["rms"]) - want_rms) <= 1,
              f"rms slots {len(v['rms'])} ≈ {want_rms} (one per 20 ms)")

        flat = [lv for row in v["grid"] for lv in row]
        check(all(0 <= lv <= 15 for lv in flat), "every spectrum nibble is 0-15")
        check(all(0 <= lv <= 15 for lv in v["rms"]), "every rms nibble is 0-15")
        check(max(flat) == 15, f"the loudest band reaches 15 (got {max(flat)})")
        check(min(flat) == 0, f"the quietest band reaches 0 (got {min(flat)})")
        check(max(v["rms"]) == 15, f"the rms envelope reaches 15 (got {max(v['rms'])})")

        # A steady sine: exactly one band should be lit, and the same one in
        # every frame. 440 Hz sits in band 14 of the 40 Hz…10.5 kHz log grid,
        # but the test asserts the SHAPE, not the index.
        mid = v["grid"][len(v["grid"]) // 2]
        loud = [i for i, lv in enumerate(mid) if lv >= 8]
        check(len(loud) == 1, f"a 440 Hz sine lights exactly one band (lit: {loud})")
        if loud:
            band = loud[0]
            steady = sum(1 for row in v["grid"][5:-5] if row[band] >= 8)
            check(steady == len(v["grid"][5:-5]),
                  f"band {band} stays lit across the body of the track "
                  f"({steady}/{len(v['grid'][5:-5])} frames)")

        check(all(0 <= t <= DURATION_S * 1000 + 50 for t in v["beats"]),
              f"beat times inside the track ({len(v['beats'])} beats)")
        check(v["beats"] == sorted(set(v["beats"])), "beat times sorted and unique")

        print("[case] -o writes the same bytes to a file, and a re-run is identical")
        out = os.path.join(work, "out.viz")
        proc2 = run_viz(wav, ["-o", out])
        check(proc2.returncode == 0, "exit 0 with -o")
        with open(out, "rb") as f:
            check(f.read() == blob, "the -o file matches the stdout blob "
                                    "(and the analysis is deterministic — the cache depends on it)")

        print("[case] a pulse train yields beats at its own tempo")
        pulse = os.path.join(work, "pulse.wav")
        # 2 Hz decaying pulses = 120 bpm; a steady sine has no onsets at all,
        # so this is the fixture that actually exercises beat_track.
        res = subprocess.run(
            ["ffmpeg", "-v", "error", "-y", "-f", "lavfi",
             "-i", "aevalsrc='0.8*sin(2*PI*440*t)*exp(-14*mod(t,0.5))':d=8:s=22050",
             "-ac", "1", pulse], capture_output=True, text=True)
        check(res.returncode == 0, "ffmpeg built the pulse fixture")
        procp = run_viz(pulse)
        check(procp.returncode == 0, "exit 0 on the pulse train")
        if procp.returncode == 0:
            p = decode_dviz(procp.stdout)
            check(len(p["beats"]) >= 8,
                  f"beats found in an 8 s 120 bpm pulse train ({len(p['beats'])})")
            if len(p["beats"]) >= 3:
                gaps = [p["beats"][i + 1] - p["beats"][i] for i in range(len(p["beats"]) - 1)]
                med = sorted(gaps)[len(gaps) // 2]
                check(abs(med - 500) <= 60,
                      f"the median beat gap is ~500 ms (got {med} ms)")

        print("[case] a missing file is a loud, non-zero failure")
        proc3 = run_viz(os.path.join(work, "nope.flac"))
        check(proc3.returncode != 0, f"non-zero exit (got {proc3.returncode})")
        check(b"viz:" in proc3.stderr, "a reason on stderr")
        check(proc3.stdout == b"", "nothing written to stdout on failure")

        print("[case] a file that is not audio fails loudly too")
        junk = os.path.join(work, "junk.mp3")
        with open(junk, "wb") as f:
            f.write(b"this is not audio\n" * 100)
        proc4 = run_viz(junk)
        check(proc4.returncode != 0, f"non-zero exit (got {proc4.returncode})")
        check(b"FAILED" in proc4.stderr or b"viz:" in proc4.stderr, "a reason on stderr")

        print("[case] a short quiet tone still produces a usable range")
        quiet = os.path.join(work, "quiet.wav")
        res = subprocess.run(
            ["ffmpeg", "-v", "error", "-y", "-f", "lavfi",
             "-i", "sine=frequency=110:duration=2", "-af", "volume=-40dB",
             "-ac", "1", quiet], capture_output=True, text=True)
        check(res.returncode == 0, "ffmpeg built the quiet fixture")
        proc5 = run_viz(quiet)
        check(proc5.returncode == 0, "exit 0 on the quiet tone")
        if proc5.returncode == 0:
            q = decode_dviz(proc5.stdout)
            qflat = [lv for row in q["grid"] for lv in row]
            check(max(qflat) == 15,
                  f"a -40 dB tone still reaches 15 (per-track percentiles; got {max(qflat)})")

    finally:
        shutil.rmtree(work, ignore_errors=True)

    print()
    if _failures:
        print(f"FAILED: {len(_failures)} check(s)")
        for f in _failures:
            print(f"  - {f}")
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
