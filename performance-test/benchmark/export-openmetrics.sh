#!/usr/bin/env bash
# Converts a benchmark run into one OpenMetrics file, e.g. before an upload, see README.md.
# The steps keep only their raw output; this conversion is not part of the run.
#
# Usage: performance-test/benchmark/export-openmetrics.sh RUN_DIR [OUT]   (default RUN_DIR/run.om)
set -euo pipefail
[ $# -ge 1 ] && [ -d "$1/raw" ] || { echo "usage: $0 RUN_DIR [OUT]" >&2; exit 1; }
exec python3 "$(dirname "$0")/benchtool.py" export "$@"
