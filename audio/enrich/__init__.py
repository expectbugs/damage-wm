# audio/enrich — the music knowledge-base builder, taken over from G2CC whole
# (`MUSIC.md` §3.12/§9.5: the G2CC music system is Damage's now; this package
# is Adam's own code, copied under his licence, not re-implemented).
#
# Runs in the audio venv against the Postgres `g2cc` database and the local
# Qdrant — the same data, the same tables, the same collection. Only the
# config seam moved: `damage_config.py` reads `~/.damage/config.json`
# (`MUSIC.md` §9.7) where `g2cc_config.py` read `~/.g2cc/config.json`.
#
# Damage is the only writer of the music tables from now on; G2CC's server
# keeps serving its setup page and its one-shot boot scan is idempotent.
