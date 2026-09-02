# The enrichment package's view of `~/.damage/config.json` (`MUSIC.md` §9.7).
#
# Damage's config file is FLAT — top-level keys, the same ones
# `desktop/src/main/kotlin/wm/damage/desktop/Main.kt` `Config` declares:
#   musicLibraryDirs  list[str]  the library roots the passes walk
#   musicLegacyCache  str        the pretranscode cache directory
#   musicAcoustidKey  str        optional; lives ONLY in this file
#
# READ-ONLY — the enrichment runner never writes config.
#
# 🔴 `cache_dir` keying must keep the legacy rule
# `{id}-{mtime_ms}-{sha1(path)[:8]}.opus`, because that cache IS the
# `standard-mono-loudnorm` profile Damage reads in place (`MUSIC.md` §6.4,
# `core/.../music/MediaCache.kt`). Change the key here and the pretranscode
# pass builds a second, unused cache.
#
# This file replaces G2CC's `g2cc_config.py` (which mirrored
# `server/src/config.ts`'s nested `music` section and `~/.g2cc/config.json`).
# The defaults below are Damage's, not G2CC's.

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field

CONFIG_PATH = os.path.expanduser("~/.damage/config.json")


@dataclass
class MusicConfig:
    library_dirs: list[str] = field(
        default_factory=lambda: [os.path.expanduser("~/Music")])
    cache_dir: str = os.path.expanduser("~/.g2cc/media-cache")
    acoustid_key: str = ""
    fmt: str = "opus"


def load_music_config() -> MusicConfig:
    """The music slice of Damage's config, with the same validation the
    Kotlin side applies. A missing file is normal on a fresh box and says so;
    an unreadable or malformed one is LOUD (house rule: no silent failures)
    and raises — a pass must not walk the wrong roots because a stray comma
    made the file unparseable."""
    cfg = MusicConfig()
    try:
        with open(CONFIG_PATH, encoding="utf-8") as f:
            raw = json.load(f)
    except FileNotFoundError:
        print(f"[config] {CONFIG_PATH} missing — using defaults "
              f"(dirs={cfg.library_dirs}, cache={cfg.cache_dir})", flush=True)
        return cfg
    except (OSError, json.JSONDecodeError) as e:
        raise RuntimeError(
            f"{CONFIG_PATH} could not be read ({e}) — refusing to fall back to "
            f"defaults, because the wrong library roots would enrich the wrong "
            f"files. Fix the file, then re-run.") from e
    if not isinstance(raw, dict):
        raise RuntimeError(f"{CONFIG_PATH} is not a JSON object (got {type(raw).__name__})")

    dirs = raw.get("musicLibraryDirs")
    if isinstance(dirs, list) and dirs and all(isinstance(d, str) and d.startswith("/") for d in dirs):
        cfg.library_dirs = dirs
    elif dirs is not None:
        # the Kotlin Config does NOT substitute — it logs the same problem and
        # runs with what the file says (desktop/.../Main.kt warnBadMusicRoots).
        # The two sides disagree deliberately: a batch pass walking the wrong
        # roots would enrich the wrong files, while the shell only loses folder
        # names. (Corrected 2026-09-02: this note used to claim both applied
        # the same rule, and the Kotlin side applied none at all.)
        print(f"[config] musicLibraryDirs invalid ({dirs!r}) — using "
              f"{cfg.library_dirs}; the Kotlin Config warns and keeps yours", flush=True)

    cache = raw.get("musicLegacyCache")
    if isinstance(cache, str) and cache.startswith("/"):
        cfg.cache_dir = cache
    elif cache is not None:
        print(f"[config] musicLegacyCache invalid ({cache!r}) — using "
              f"{cfg.cache_dir}", flush=True)

    key = raw.get("musicAcoustidKey")
    if isinstance(key, str) and key.strip():
        cfg.acoustid_key = key.strip()
    elif key is not None and not isinstance(key, str):
        print(f"[config] musicAcoustidKey is not a string ({type(key).__name__}) — treating as absent", flush=True)

    return cfg
