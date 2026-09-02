# Pass 0 — consistency: walk the library, compare against the tracks index.
# INSERTS files the index is missing (the same `tracks` columns the indexer
# writes, via videosweep.index_file); REPORTS rows whose files vanished or
# drifted but NEVER deletes — deletion scoping is the indexer's carefully
# guarded logic (the 2026-08-04 unmounted-root lesson: an unmounted library
# root looks exactly like a library that lost every file) and is not
# reimplemented here. Damage's own `LibraryScan` is the indexer now.

from __future__ import annotations

import os

from .. import db
from ..damage_config import load_music_config
from ..util import AUDIO_EXTS
from .videosweep import index_file


def _walk_audio(root: str) -> list[str]:
    hits: list[str] = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d != "lost+found" and not d.startswith(".")]
        for f in filenames:
            if os.path.splitext(f)[1].lower() in AUDIO_EXTS:
                hits.append(os.path.join(dirpath, f))
    return hits


def run(conn, force: bool = False, limit: int | None = None,
        track_id: int | None = None) -> None:
    cfg = load_music_config()
    on_disk: set[str] = set()
    for root in cfg.library_dirs:
        if not os.path.isdir(root):
            print(f"[consistency] root unreadable, skipped: {root}", flush=True)
            continue
        on_disk.update(_walk_audio(root))
    rows = db.all_tracks(conn)
    in_db = {t["path"]: t for t in rows}

    missing_from_db = sorted(on_disk - in_db.keys())
    if limit:
        missing_from_db = missing_from_db[:limit]
    vanished = sorted(in_db.keys() - on_disk)
    # a bare os.stat here ended the whole pass the first time a file moved
    # between the walk and this loop (a live music library, a 3,000-track
    # batch): an unreadable file is REPORTED and the pass continues, which is
    # what every other per-track failure in this package does.
    drifted: list[str] = []
    unreadable: list[str] = []
    for p in sorted(on_disk & in_db.keys()):
        try:
            disk_ms = round(os.stat(p).st_mtime * 1000)
        except OSError as e:
            unreadable.append(f"{p}: {e}")
            continue
        db_ms = in_db[p]["mtime_ms"]
        if db_ms is None or disk_ms != int(db_ms):
            drifted.append(p)

    inserted = failed = 0
    for p in missing_from_db:
        try:
            tid = index_file(conn, p)
            inserted += 1
            print(f"[consistency] indexed missing file → #{tid} {p}", flush=True)
        except Exception as e:  # noqa: BLE001
            failed += 1
            print(f"[consistency] index FAILED {p}: {e}", flush=True)
    if inserted:
        db.ensure_schema(conn)   # meta rows for the new tracks

    print(f"[consistency] disk={len(on_disk)} db={len(in_db)} → "
          f"+{inserted} indexed ({failed} failed); "
          f"{len(vanished)} DB rows with missing files (REPORT ONLY — server scan owns deletion); "
          f"{len(drifted)} mtime-drifted (server scan will re-probe)"
          + (f"; {len(unreadable)} unreadable while comparing mtimes" if unreadable else ""), flush=True)
    for u in unreadable[:20]:
        print(f"[consistency]   unreadable: {u}", flush=True)
    if len(unreadable) > 20:
        print(f"[consistency]   … and {len(unreadable) - 20} more", flush=True)
    for p in vanished[:20]:
        print(f"[consistency]   vanished: {p}", flush=True)
    if len(vanished) > 20:
        print(f"[consistency]   … and {len(vanished) - 20} more", flush=True)
