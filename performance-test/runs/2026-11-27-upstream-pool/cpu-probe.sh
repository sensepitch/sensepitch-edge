#!/bin/bash
#
# Runs ./probe.sh <rate> and samples the CPU once per second while vegeta sends the measured rate,
# after the warm-up of probe.sh: the whole machine
# (mpstat), the edge and vegeta (pidstat by PID), the NGINX processes of the containers, and the
# threads of the edge. Needs sysstat (pidstat, mpstat).
#
# Usage: ./cpu-probe.sh <rate>     e.g. SENSEPITCH_EDGE_REQUEST_LOG_DISABLED=true ./cpu-probe.sh 10000
#
# Output: results-cpu/<rate>-*.txt, and the files of probe.sh in results-probe/
set -e
RATE=${1:?usage: ./cpu-probe.sh <rate>}
BENCH=$(cd "$(dirname "$0")" && pwd)
OUT=$BENCH/results-cpu
mkdir -p "$OUT"
NAME=$OUT/$RATE${SENSEPITCH_EDGE_REQUEST_LOG_DISABLED:+-nolog}
SECONDS_SAMPLED=10

"$BENCH/probe.sh" "$RATE" > "$NAME-probe.txt" 2>&1 &
PROBE=$!

# the vegeta of the measurement, after the warm-up of probe.sh, see its -rate; [v] keeps pgrep
# from matching this script's own command
for i in $(seq 900); do
  VEGETA=$(pgrep -d, -f -- "[v]egeta attack .*-rate=$RATE ") && break
  sleep 0.1
done
if [ -z "$VEGETA" ]; then echo "vegeta did not start, see $NAME-probe.txt" >&2; exit 1; fi
# the JVM of the edge: [.] keeps the pattern from matching a shell that contains it
EDGE=$(pgrep -d, -f -- '-jar .*pooled[.]jar')

mpstat 1 "$SECONDS_SAMPLED" > "$NAME-machine.txt" &
pidstat -u -p "$EDGE,$VEGETA" 1 "$SECONDS_SAMPLED" > "$NAME-processes.txt" &
pidstat -u -C nginx 1 "$SECONDS_SAMPLED" > "$NAME-nginx.txt" &
pidstat -u -t -p "$EDGE" 1 "$SECONDS_SAMPLED" > "$NAME-threads.txt" &
wait $PROBE || true
wait

echo "== $RATE requests per second, request log disabled: ${SENSEPITCH_EDGE_REQUEST_LOG_DISABLED:-false}"
grep -E "Latencies|Success" "$NAME-probe.txt"
echo "== machine, average of $SECONDS_SAMPLED s"
grep -E "^Average" "$NAME-machine.txt"
echo "== processes, average %CPU (100 = one core)"
grep -E "^Average" "$NAME-processes.txt"
echo "== all nginx processes together, average %CPU"
awk '/^Average/ && $NF == "nginx" {sum += $(NF-2)} END {print sum}' "$NAME-nginx.txt"
echo "== edge threads by name, average %CPU summed per name prefix"
awk '/^Average/ && $NF ~ /^\|__/ {
    name = $NF; sub(/^\|__/, "", name); sub(/-[0-9]+$/, "", name); sub(/[0-9]+$/, "", name)
    sum[name] += $(NF-2); count[name]++
  } END { for (n in sum) printf "%7.1f %3d threads  %s\n", sum[n], count[n], n }' \
  "$NAME-threads.txt" | sort -rn | head -12
