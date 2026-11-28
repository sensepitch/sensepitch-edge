#!/usr/bin/env bash
# knockout test: writes summary.md from the raw output of the steps, see ../README.md.
# Fails if a step of the mode's matrix has no result.
set -euo pipefail
# shellcheck source=../lib.sh
source "$(dirname "$0")/../lib.sh"
missing=0
while read -r proxy size log conns repeat; do
  raw="$BENCH_RUN_DIR/raw/$proxy-$size-log-$log-c$conns-r$repeat"
  if [ ! -s "$raw.meta.json" ] || [ ! -s "$raw.shb.json" ]; then
    echo "no result of step $(basename "$raw")" >&2
    missing=1
  fi
done < <(bench_matrix)
[ "$missing" -eq 0 ] || exit 1
python3 "$BENCH_DIR/benchtool.py" summary "$BENCH_RUN_DIR"
