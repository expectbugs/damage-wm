# The enrichment runner (taken over from G2CC; its decision record is
# /home/user/G2CC/docs/MUSIC_SPEC.md D3.2, read-only). Run from `audio/` on
# the music venv (`MUSIC.md` §9.5, config key `musicPython`):
#
#   cd <repo>/audio && /home/user/G2CC/audio/venv/bin/python \
#       -m enrich.run_enrichment <pass> --track-id N
#
# The Kotlin `Enrich` ingester (core/.../windows/music/Enrich.kt) drives
# exactly that command line for one new track, pass by pass.
#
# Passes: consistency videosweep tags musicbrainz lyrics audio speech profile
#         embed dedupe pretranscode acoustid report all
#         (speech + acoustid are NOT in `all` — scope with --ids/--track-id)
# Flags:  --force (redo done tracks) --limit N --track-id N --concurrency N
#
# Every pass is resumable (per-track pass_status in track_meta) and safe to
# run concurrently with the others (row-locked jsonb merges). `all` runs the
# full ordered sequence SERIALLY — the parallel orchestration for the big
# first run lives in the operator's shell, not here.

from __future__ import annotations

import argparse
import os
import sys
from datetime import date

from . import db, report
from .passes import (audio_feats, backfill_acoustid, consistency, dedupe,
                     lyrics, musicbrainz, pretranscode, profile, speech, tags,
                     videosweep)
from .passes import embed as embed_pass

PASSES = {
    "consistency": consistency.run,
    "videosweep": videosweep.run,
    "tags": tags.run,
    "musicbrainz": musicbrainz.run,
    "lyrics": lyrics.run,
    "audio": audio_feats.run,
    "speech": speech.run,
    "profile": profile.run,
    "embed": embed_pass.run,
    "dedupe": dedupe.run,
    "pretranscode": pretranscode.run,
    # Phase E backfill (D3.2 #10) — NOT in `all` (like speech): evidence-only
    # fingerprint identification; keyless-guarded (Adam's D11#2 key unlocks it).
    "acoustid": backfill_acoustid.run,
}
ALL_ORDER = ["consistency", "videosweep", "tags", "musicbrainz", "lyrics",
             "audio", "pretranscode", "dedupe", "profile", "embed"]
CONCURRENCY_AWARE = {"tags", "audio", "profile", "pretranscode"}


def main() -> int:
    ap = argparse.ArgumentParser(description="Damage music enrichment")
    ap.add_argument("passname", choices=[*PASSES.keys(), "all", "report"])
    ap.add_argument("--force", action="store_true", help="re-run tracks already marked ok")
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--track-id", type=int, default=None)
    ap.add_argument("--concurrency", type=int, default=None)
    ap.add_argument("--report-out", default=None)
    ap.add_argument("--artistless", action="store_true",
                    help="speech/acoustid passes: restrict to tracks with no artist tag")
    ap.add_argument("--ids", default=None,
                    help="speech/acoustid passes: comma-separated track ids")
    args = ap.parse_args()

    conn = db.connect()
    db.ensure_schema(conn)

    if args.passname == "report":
        # Default beside this package (it moved with the takeover); the
        # directory is created on demand and is git-ignored — report output
        # is an operator artifact, not source.
        default_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "reports")
        out = args.report_out or os.path.join(default_dir, f"{date.today().isoformat()}-enrichment.md")
        os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
        report.run(conn, out)
        return 0

    names = ALL_ORDER if args.passname == "all" else [args.passname]
    for name in names:
        print(f"===== pass: {name} =====", flush=True)
        kwargs = dict(force=args.force, limit=args.limit, track_id=args.track_id)
        if args.concurrency is not None and name in CONCURRENCY_AWARE:
            kwargs["concurrency"] = args.concurrency
        if name in ("speech", "acoustid"):
            kwargs["artistless"] = args.artistless
            if args.ids:
                kwargs["ids"] = [int(x) for x in args.ids.split(",") if x.strip()]
        PASSES[name](conn, **kwargs)
    return 0


if __name__ == "__main__":
    sys.exit(main())
