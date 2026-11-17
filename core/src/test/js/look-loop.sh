#!/bin/bash
# Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
# L1 (the gate's harness G1, standing since R2 A7): TEST CODE ONLY. Runs the look's listener (LookServer) and, for each
# line appended to RESTART_DIR/trig (the front's `/__restart`), ends its JVM with SIGTERM (as a deploy's stop does) and
# starts it again with the same environment: LOOK_PORT and LOOK_ROOT must be set, so the same port and directory come
# back. Each start that prints its `LOOK {` line adds a line to RESTART_DIR/ack. RESTART_DIR/end ends the loop and the
# JVM. The classpath file is `./gradlew :core:lookClasspath`'s (core/build/look.classpath).
# usage: look-loop.sh RESTART_DIR CLASSPATH_FILE WORK_DIR
R="$1"; CP="$(cat "$2")"; WORK="$3"
[ -n "$LOOK_PORT" ] && [ -n "$LOOK_ROOT" ] || { echo "look-loop: set LOOK_PORT and LOOK_ROOT (a restart keeps both)" >&2; exit 64; }
: > "$R/trig"; : > "$R/ack"; : > "$R/pids"
n=0; pid=
stop() { if [ -n "$pid" ]; then kill -TERM "$pid" 2>/dev/null; wait "$pid"; echo "STOPPED $pid by signal" >> "$R/server.log"; fi; exit 0; }
trap stop TERM INT
while :; do
  java -Xmx1g -cp "$CP" wm.damage.core.LookServerKt "$WORK" >> "$R/server.log" 2>&1 &
  pid=$!; echo "$pid" >> "$R/pids"
  while [ "$(grep -c '^LOOK {' "$R/server.log")" -le "$(wc -l < "$R/ack")" ]; do
    kill -0 "$pid" 2>/dev/null || { echo "JVM $pid ended before it was up" >> "$R/server.log"; exit 1; }
    sleep 0.2
  done
  echo "up $pid $(date +%s.%N)" >> "$R/ack"
  while :; do
    if [ -e "$R/end" ]; then kill -TERM "$pid"; wait "$pid"; echo "END $pid exit $?" >> "$R/server.log"; exit 0; fi
    c=$(wc -l < "$R/trig"); if [ "$c" -gt "$n" ]; then n=$c; break; fi
    kill -0 "$pid" 2>/dev/null || { echo "JVM $pid ended by itself" >> "$R/server.log"; exit 1; }
    sleep 0.2
  done
  kill -TERM "$pid"; wait "$pid"; echo "RESTART: $pid ended with exit $? at $(date +%s.%N)" >> "$R/server.log"
done
