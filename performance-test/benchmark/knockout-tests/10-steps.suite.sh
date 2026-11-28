#!/usr/bin/env bash
# knockout testsuite: one test per measured step of the mode's matrix, see ../README.md.
# Without arguments: the listing ("#!testsuite", then "proxy size log connections repeat" per
# step, exit 88). With arguments: runs that step. Steps run one after the other; never run them
# in parallel (knockout -j), they would measure each other.
set -uo pipefail
# shellcheck source=../lib.sh
source "$(dirname "$0")/../lib.sh"
if [ $# -eq 0 ]; then
  echo "#!testsuite"
  bench_matrix
  exit 88
fi
bench_step "$@"
