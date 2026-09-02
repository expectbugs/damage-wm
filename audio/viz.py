#!/usr/bin/env python3
"""Visualizer precompute — one audio file in, one DVIZ blob out.

    cd <repo>/audio
    /home/user/G2CC/audio/venv/bin/python -m viz "/home/user/Music/…/x.flac" > out.viz
    /home/user/G2CC/audio/venv/bin/python viz.py -o out.viz "…/x.flac"

`MUSIC.md` §3.8/§6.4: the PC computes the visualizer data once, at transcode
time, and the phone renders from data + playback position — no microphone
permission, nothing analysed on the phone. Three streams:

  * a 24-band log-spaced spectrum envelope at 20 fps, 4-bit levels
  * an RMS envelope per 20 ms, 4-bit levels
  * beat times in ms (librosa.beat.beat_track)

The output format is DVIZ v1, defined by `VizData` in
`core/src/main/kotlin/wm/damage/core/windows/music/MusicModel.kt` — magic
"DVIZ", u8 version, u8 fps, u8 bands, u32 LE frames / rmsCount / beats, then
frames×bands nibbles packed high-first row-major, the rms nibbles, and the
beat times as u32 LE. `VizData.encode()` writes exactly these bytes and
`VizData.decode()` reads them; this file is the other end of that contract.

Deliberately NOT done here: smoothing, gravity, peak-hold. Those belong to
the renderers (`Viz.kt` — BarsViz's gravity and friends), so the stored data
stays the honest measurement and a renderer change needs no re-analysis.

Failures are loud: a reason on stderr and a non-zero exit. Nothing is
truncated — the whole track is analysed, however long it is.
"""

from __future__ import annotations

import argparse
import contextlib
import math
import os
import subprocess
import sys
import tempfile

FPS = 20                  # spectrum frames per second (u8 in the header)
BANDS = 24                # log-spaced bands (u8 in the header)
RMS_SLOT_MS = 20          # one RMS level per 20 ms, per the format
SR = 22050                # analysis rate: mono 22050 Hz, as audio_feats uses

N_FFT = 2048              # 93 ms window at 22050 Hz
HOP = 512                 # 23.2 ms — finer than the 50 ms output frame, so
                          # every output frame averages 2-3 analysis frames

BAND_LO_HZ = 40.0         # below this is rumble the panel cannot show anyway
BAND_HI_FRAC = 0.95       # top edge as a fraction of Nyquist (10.5 kHz @ 22050)

# Pink-noise compensation. Music falls off roughly 3-6 dB per octave, so
# without a tilt the top bands sit at the floor for every track and a
# 24-band display is really a 10-band display. +3 dB/octave relative to
# 1 kHz is the standard correction (what cava-class visualizers apply).
TILT_DB_PER_OCTAVE = 3.0
TILT_REF_HZ = 1000.0

# The per-track level window (§6.4 "use percentiles per track so every track
# uses the range"): the top is the 99th percentile, the bottom the 15th, and
# the window is then held between MIN and MAX decibels wide — MIN so a nearly
# constant track does not turn its own noise floor into full-range flicker,
# MAX so one silent intro does not push every real level into the top third.
P_HI = 99.0
P_LO = 15.0
MIN_RANGE_DB = 24.0
MAX_RANGE_DB = 60.0

FLOOR_DB = -100.0         # digital silence clamps here before the mapping

MAGIC = b"DVIZ"
VERSION = 1


# ----------------------------------------------------------------- decoding

def _decode(path: str):
    """Mono samples at SR. librosa first; ffmpeg to a temp wav when librosa
    cannot open the container (wma, odd m4a, a video wrapper). Returns
    (samples, sr)."""
    import librosa
    import numpy as np

    try:
        y, sr = librosa.load(path, sr=SR, mono=True)
    except Exception as e:  # noqa: BLE001 — reported, then retried via ffmpeg
        print(f"viz: librosa could not open {path!r} ({e}) — decoding with ffmpeg",
              file=sys.stderr, flush=True)
        y, sr = _decode_via_ffmpeg(path)
    if y is None or y.size == 0:
        raise RuntimeError(f"{path}: decoded 0 samples — the file or its container is unusable")
    if y.size < sr // 10:
        # Real in this library (short sound effects). Say so; still analyse it.
        print(f"viz: {path} decoded only {y.size} samples ({y.size / sr:.3f} s) — "
              f"analysing it anyway", file=sys.stderr, flush=True)
    return np.asarray(y, dtype=np.float32), int(sr)


def _decode_via_ffmpeg(path: str):
    import librosa

    fd, tmp = tempfile.mkstemp(suffix=".wav",
                               dir=os.environ.get("DAMAGE_ENRICH_TMP") or None)
    os.close(fd)
    try:
        # No timeout: the house rule stands, and ffmpeg on a local file
        # either finishes or reports why.
        res = subprocess.run(
            ["ffmpeg", "-v", "error", "-y", "-i", path,
             "-map", "0:a:0", "-vn", "-ac", "1", "-ar", str(SR), "-f", "wav", tmp],
            capture_output=True, text=True)
        if res.returncode != 0:
            raise RuntimeError(f"ffmpeg rc={res.returncode} on {path}: "
                               f"{res.stderr.strip()[:300]}")
        return librosa.load(tmp, sr=None)
    finally:
        try:
            os.unlink(tmp)
        except OSError as e:
            print(f"viz: could not remove the temp wav {tmp} ({e})",
                  file=sys.stderr, flush=True)


# ------------------------------------------------------------------- levels

def _band_edges(sr: int):
    """BANDS+1 log-spaced edges from BAND_LO_HZ to just under Nyquist."""
    import numpy as np

    hi = min(sr * 0.5 * BAND_HI_FRAC, 16000.0)
    if hi <= BAND_LO_HZ:
        raise RuntimeError(f"sample rate {sr} leaves no usable band range")
    return np.logspace(math.log10(BAND_LO_HZ), math.log10(hi), BANDS + 1)


def _band_bins(freqs, edges):
    """For each band, the FFT bin indices inside it. A band narrower than the
    bin spacing (the lowest few at 22050/2048 = 10.8 Hz) gets the single
    nearest bin, so no band is ever empty and silently reads 0."""
    import numpy as np

    out = []
    for b in range(BANDS):
        lo, hi = edges[b], edges[b + 1]
        idx = np.nonzero((freqs >= lo) & (freqs < hi))[0]
        if idx.size == 0:
            centre = math.sqrt(lo * hi)
            idx = np.array([int(np.argmin(np.abs(freqs - centre)))])
        out.append(idx)
    return out


def _to_levels(db, name: str):
    """dB values → 0-15 through the per-track percentile window. Returns
    (levels as uint8, lo, hi) — the caller logs the window it used."""
    import numpy as np

    flat = db.reshape(-1)
    hi = float(np.percentile(flat, P_HI))
    lo = float(np.percentile(flat, P_LO))
    lo = max(lo, hi - MAX_RANGE_DB)
    lo = min(lo, hi - MIN_RANGE_DB)
    span = hi - lo
    if not math.isfinite(span) or span <= 0:
        raise RuntimeError(f"{name}: level window collapsed (lo={lo}, hi={hi}) — "
                           f"the decoded audio is degenerate")
    lv = np.clip((db - lo) / span, 0.0, 1.0) * 15.0
    return np.rint(lv).astype(np.uint8), lo, hi


def _spectrum(y, sr: int, frames: int):
    """frames × BANDS levels, 0-15, on the exact FPS grid."""
    import librosa
    import numpy as np

    S = np.abs(librosa.stft(y, n_fft=N_FFT, hop_length=HOP)) ** 2   # power
    freqs = librosa.fft_frequencies(sr=sr, n_fft=N_FFT)
    edges = _band_edges(sr)
    bins = _band_bins(freqs, edges)

    band_power = np.empty((BANDS, S.shape[1]), dtype=np.float64)
    for b, idx in enumerate(bins):
        band_power[b] = S[idx].mean(axis=0)

    # Analysis frames land on the output grid by TIME, so the 20 fps stream
    # is exact whatever the hop is (22050/20 is not an integer — resampling
    # by index would drift ~0.1 s across a long track and desync the bars).
    t = librosa.frames_to_time(np.arange(S.shape[1]), sr=sr, hop_length=HOP)
    slot = np.clip((t * FPS).astype(np.int64), 0, frames - 1)
    acc = np.zeros((frames, BANDS), dtype=np.float64)
    cnt = np.zeros(frames, dtype=np.int64)
    np.add.at(acc, slot, band_power.T)
    np.add.at(cnt, slot, 1)
    empty = cnt == 0
    acc[~empty] /= cnt[~empty][:, None]
    if empty.any():
        # Only possible at the tail when the last analysis window ends early;
        # hold the previous frame rather than dropping a hole into the bars.
        for k in np.nonzero(empty)[0]:
            acc[k] = acc[k - 1] if k > 0 else 0.0

    db = 10.0 * np.log10(np.maximum(acc, 1e-10))
    centres = np.sqrt(edges[:-1] * edges[1:])
    db += TILT_DB_PER_OCTAVE * np.log2(centres / TILT_REF_HZ)[None, :]
    db = np.maximum(db, FLOOR_DB)
    return _to_levels(db, "spectrum")


def _rms(y, sr: int, count: int):
    """One level per RMS_SLOT_MS, 0-15. Computed directly (not via librosa)
    so slot i is exactly [i·20 ms, (i+1)·20 ms) with no centring offset."""
    import numpy as np

    slot = max(1, int(round(sr * RMS_SLOT_MS / 1000.0)))
    need = count * slot
    x = y[:need] if y.size >= need else np.pad(y, (0, need - y.size))
    blocks = x.reshape(count, slot).astype(np.float64)
    r = np.sqrt((blocks * blocks).mean(axis=1))
    db = np.maximum(20.0 * np.log10(np.maximum(r, 1e-10)), FLOOR_DB)
    return _to_levels(db, "rms")


def _beats(y, sr: int, dur_ms: int):
    import librosa
    import numpy as np

    tempo, times = librosa.beat.beat_track(y=y, sr=sr, units="time")
    ms = np.rint(np.asarray(times, dtype=np.float64) * 1000.0).astype(np.int64)
    ms = np.unique(ms[(ms >= 0) & (ms <= dur_ms)])
    return ms.astype(np.uint32), float(np.atleast_1d(tempo)[0])


# ------------------------------------------------------------------ packing

def _pack_nibbles(levels) -> bytes:
    """High nibble first, row-major — VizData.nib()'s layout: nibble i is the
    high half of byte i/2 when i is even, the low half when it is odd."""
    import numpy as np

    flat = np.asarray(levels, dtype=np.uint8).reshape(-1)
    if flat.size % 2:
        flat = np.append(flat, np.uint8(0))
    packed = (flat[0::2] << np.uint8(4)) | flat[1::2]
    return packed.astype(np.uint8).tobytes()


def _u32(v: int) -> bytes:
    return int(v).to_bytes(4, "little", signed=False)


def encode_dviz(fps: int, bands: int, frames, rms, beats_ms) -> bytes:
    """The DVIZ v1 blob, byte for byte as VizData.encode() writes it."""
    frame_count = frames.shape[0]
    rms_count = rms.shape[0]
    out = bytearray(MAGIC)
    out += bytes([VERSION, fps & 0xFF, bands & 0xFF])
    out += _u32(frame_count) + _u32(rms_count) + _u32(len(beats_ms))
    out += _pack_nibbles(frames)
    out += _pack_nibbles(rms)
    for b in beats_ms:
        out += _u32(int(b))
    return bytes(out)


# --------------------------------------------------------------------- main

def build(path: str) -> bytes:
    import numpy as np

    y, sr = _decode(path)
    dur_s = y.size / sr
    dur_ms = int(round(dur_s * 1000))
    frames = max(1, int(math.ceil(dur_s * FPS)))
    rms_count = max(1, int(math.ceil(dur_ms / RMS_SLOT_MS)))

    spec, s_lo, s_hi = _spectrum(y, sr, frames)
    rms, r_lo, r_hi = _rms(y, sr, rms_count)
    beats, tempo = _beats(y, sr, dur_ms)

    blob = encode_dviz(FPS, BANDS, spec, rms, beats)
    print(f"viz: {os.path.basename(path)} — {dur_s:.1f} s, {frames} frames × {BANDS} bands "
          f"@ {FPS} fps, {rms_count} rms slots, {len(beats)} beats "
          f"({tempo:.1f} bpm), {len(blob)} B; "
          f"spectrum window {s_lo:.1f}…{s_hi:.1f} dB, rms window {r_lo:.1f}…{r_hi:.1f} dB",
          file=sys.stderr, flush=True)
    return blob


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        prog="viz",
        description="Compute the DVIZ visualizer blob for one audio file "
                    "(MUSIC.md §6.4). The blob goes to stdout unless -o is given.")
    ap.add_argument("audio", help="path to the audio file")
    ap.add_argument("-o", "--out", default=None,
                    help="write the blob here instead of stdout")
    args = ap.parse_args(argv)

    if not os.path.isfile(args.audio):
        print(f"viz: no such file: {args.audio}", file=sys.stderr, flush=True)
        return 2
    if args.out is None and sys.stdout.isatty():
        print("viz: stdout is a terminal and the blob is binary — redirect it "
              "(> out.viz) or pass -o FILE.", file=sys.stderr, flush=True)
        return 2

    try:
        # librosa and its backends print progress and warnings on stdout;
        # divert those to stderr so stdout carries ONLY the blob (the same
        # guard enrich/embed_query.py uses for its JSON vector).
        with contextlib.redirect_stdout(sys.stderr):
            blob = build(args.audio)
    except Exception as e:  # noqa: BLE001 — loud, with the reason, then non-zero
        print(f"viz: FAILED on {args.audio}: {type(e).__name__}: {e}",
              file=sys.stderr, flush=True)
        return 1

    if args.out:
        tmp = args.out + ".part"
        with open(tmp, "wb") as f:
            f.write(blob)
        os.replace(tmp, args.out)
    else:
        sys.stdout.buffer.write(blob)
        sys.stdout.buffer.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
