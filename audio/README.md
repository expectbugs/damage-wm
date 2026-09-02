# `audio/` — the Python side of the Music window

Two things live here, both driven from Kotlin by
`core/src/main/kotlin/wm/damage/core/windows/music/Enrich.kt`:

| | what it does |
|---|---|
| `enrich/` | the music knowledge-base builder — tags, MusicBrainz, lyrics, audio features, the language-model profile, embeddings, dedupe, the transcode cache. **Adam's own code, taken over whole from G2CC** (`MUSIC.md` §1 verdict 1, §3.12, §9.5). |
| `viz.py` | the visualizer precompute: one audio file in, one DVIZ blob out (`MUSIC.md` §3.8, §6.4). New. |

Everything runs against the Postgres database `g2cc` and the Qdrant
collection `g2cc_music`. Those names are history, not a dependency: Damage
took the music system over in place, and **Damage is the only writer of the
music tables from now on**. G2CC on disk is read-only to us.

---

## Running it

There is no Damage-owned venv yet. Use G2CC's (the config key `musicPython`,
default `/home/user/G2CC/audio/venv/bin/python`) and run **from this
directory**, because `-m enrich.…` and `-m viz` resolve against the working
directory:

```sh
cd <repo>/audio

# one enrichment pass for one track
/home/user/G2CC/audio/venv/bin/python -m enrich.run_enrichment tags --track-id 1234

# the whole ordered sequence over the library (long; run it in tmux)
/home/user/G2CC/audio/venv/bin/python -m enrich.run_enrichment all

# the coverage report
/home/user/G2CC/audio/venv/bin/python -m enrich.run_enrichment report

# the visualizer blob for one file
/home/user/G2CC/audio/venv/bin/python -m viz "/home/user/Music/…/track.flac" > track.viz
/home/user/G2CC/audio/venv/bin/python viz.py -o track.viz "/home/user/Music/…/track.flac"
```

`Enrich.kt` runs exactly the first and last of those for every newly grabbed
track, in this order:

```
tags · musicbrainz · lyrics · audio · profile · embed · dedupe   then   viz
```

A pass that fails is logged with the head of its stderr and the chain
continues — a track that plays never stops playing because a web service was
unreachable.

### Passes

| pass | what it writes |
|---|---|
| `consistency` | library-wide sweep: indexes files the `tracks` table is missing, reports drift, never deletes |
| `videosweep` | extracts audio from stray video containers into `<stem>.g2cc-audio.<ext>` siblings and indexes them |
| `tags` | `sources.tags` — the full ffprobe tag set the indexer discards |
| `musicbrainz` | `sources.musicbrainz` — recording + artist-tag lookups, 1 req/s |
| `lyrics` | the `lyrics` table via LRCLIB (positive **and** negative results cached) |
| `audio` | `bpm` + `sources.audio` — librosa features (RMS, centroid, rolloff, onset) |
| `speech` | `sources.speech` — ASR vocal-presence detection; opt-in, slow, not in `all` |
| `profile` | the descriptive columns (genres/styles/moods/energy/year/vocals/language/themes/description) via one `claude --print` call per batch |
| `embed` | 384-dim `bge-small-en-v1.5` vectors into Qdrant `g2cc_music` |
| `dedupe` | `dupe_cluster` labels |
| `pretranscode` | the opus 96 k mono loudnorm cache — see the warning below |
| `acoustid` | fingerprint evidence into `sources.acoustid`; needs a key, opt-in, not in `all` |

Flags: `--force` `--limit N` `--track-id N` `--concurrency N`, plus
`--ids`/`--artistless` for `speech` and `acoustid`. Every pass is resumable
(per-track `pass_status` in `track_meta`) and safe to re-run.

### Configuration

`enrich/damage_config.py` reads the flat top-level keys of
`~/.damage/config.json` (`MUSIC.md` §9.7), the same file the Kotlin `Config`
reads:

| key | used for |
|---|---|
| `musicLibraryDirs` | the roots `consistency` / `videosweep` walk |
| `musicLegacyCache` | where `pretranscode` writes |
| `musicAcoustidKey` | the `acoustid` pass (optional; `ACOUSTID_API_KEY` wins) |

A missing config file falls back to the documented defaults and says so; an
unreadable or malformed one raises, rather than quietly enriching the wrong
files.

Environment overrides: `DAMAGE_ENRICH_TMP` (scratch directory for the decode
temporaries), `CLAUDE_CLI`, `DAMAGE_CLAUDE_MODEL` / `DAMAGE_CLAUDE_EFFORT`
(the profile pass's one-shot), `DAMAGE_PARAKEET_DIR` (the ASR engine the
`speech` pass borrows from G2CC's dictation pipeline — the one thing that did
not move with the takeover).

> ⚠ **Do not change the `pretranscode` key rule.**
> `{id}-{mtime_ms}-{sha1(path)[:8]}.opus`, opus 96 k mono with
> `loudnorm=I=-16:TP=-1.5:LRA=11`. That cache *is* Damage's
> `standard-mono-loudnorm` profile, read in place by `MediaCache.kt`
> (`MUSIC.md` §6.4). A different key builds a second, unused 8 GB cache.

---

## `viz.py`

```
viz.py <audio file> [-o OUT]     # blob on stdout unless -o is given
```

Decodes to mono 22050 Hz with librosa (ffmpeg to a temp wav when librosa
cannot open the container), then emits **DVIZ v1**:

* a **24-band log-spaced spectrum envelope at 20 fps**, 4-bit levels — 40 Hz
  to just under Nyquist, pink-compensated (+3 dB/octave) so the top bands are
  not permanently dark, mapped through a per-track percentile window so a
  quiet track uses the full 0–15 range;
* an **RMS envelope per 20 ms**, 4-bit, mapped the same way;
* **beat times in ms** from `librosa.beat.beat_track`.

The byte layout is fixed by `VizData` in
`core/.../windows/music/MusicModel.kt`: magic `DVIZ`, u8 version, u8 fps, u8
bands, u32 LE frames / rmsCount / beats, then frames×bands nibbles packed
high-first row-major, the rms nibbles, and the beat times as u32 LE.
`VizData.encode()` writes exactly these bytes and `VizData.decode()` reads
them.

No smoothing, gravity or peak-hold is applied — those belong to the
renderers (`Viz.kt`), so the stored data stays the honest measurement and a
renderer change needs no re-analysis.

**Measured**, not modeled (2026-09-02, this box): a 6.5-minute FLAC takes
5.5 s and produces a 107 KB blob; roughly 16 KB per minute of audio. Nothing
is truncated — the whole track is analysed however long it is.

Failures are loud: a reason on stderr and a non-zero exit (2 = bad arguments
or a missing file, 1 = the analysis failed). Nothing is written to stdout on
a failure.

### Its test

```sh
cd <repo>/audio
/home/user/G2CC/audio/venv/bin/python tests/test_viz.py
```

No pytest, no network, no database. It synthesizes its fixtures with ffmpeg
in a temp directory, runs `viz.py` the way `Enrich.kt` does, and decodes the
result with its own reader written from the `VizData` layout — so the two
encoders have to agree on the bytes. The Kotlin side of the same bridge is
`core/src/test/kotlin/wm/damage/core/EnrichTest.kt`.

---

## The venv

`requirements-frozen.txt` is a `pip freeze` of
`/home/user/G2CC/audio/venv` as it stood on 2026-09-02 (224 packages;
Python 3.13.14, librosa 0.11.0, numpy 2.4.4, psycopg 3.3.4, torch 2.12.0,
transformers 4.57.6). It is a record of what these passes actually ran
against, not an install list — a Damage-owned venv is a later chore, and
most of those 224 packages belong to G2CC's other tooling.
