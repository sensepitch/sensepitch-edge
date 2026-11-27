#!/bin/bash
#
# One load step against the edge with the upstream connection pool: starts the containers and the
# edge (pooled.jar in this folder), warms the edge up with GET /10kb.img at WARMUP_RATE requests per
# second for WARMUP_SECONDS (report discarded), then sends <rate> requests per second for 10 s with
# vegeta, prints the vegeta report, the ports in TIME_WAIT by kind and the pool metrics of the edge
# (they include the warm-up), and stops everything. Without the warm-up the first seconds measure
# the JIT compilation of the freshly started edge, see the README.
#
# Usage: ./probe.sh <rate>     e.g. ./probe.sh 5000
# Warm-up: WARMUP_RATE (default 1000), WARMUP_SECONDS (default 10, 0 for none)
# Further SENSEPITCH_EDGE_* variables of the caller apply too, e.g. without the request log:
#        SENSEPITCH_EDGE_REQUEST_LOG_DISABLED=true ./probe.sh 5000
#
# Output: results-probe/<rate>-pool[-nolog].txt (report and metrics) and .log (edge output)
set -e
RATE=${1:?usage: ./probe.sh <rate>}
WARMUP_RATE=${WARMUP_RATE:-1000}
WARMUP_SECONDS=${WARMUP_SECONDS:-10}
BENCH=$(cd "$(dirname "$0")" && pwd)
OUT=$BENCH/results-probe
mkdir -p "$OUT"
NAME=$OUT/$RATE-pool${SENSEPITCH_EDGE_REQUEST_LOG_DISABLED:+-nolog}
cd "$BENCH/../.."

docker compose up -d > /dev/null 2>&1
UPSTREAM_IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' \
  "$(docker compose ps -q nginx-static)")
./wait-for-it.sh "$UPSTREAM_IP:80" --timeout=10 > /dev/null

export SENSEPITCH_EDGE_LISTEN_ADDRESS=0.0.0.0
export SENSEPITCH_EDGE_LISTEN_HTTPS_PORT=17443
export SENSEPITCH_EDGE_LISTEN_SSL_KEY_PATH=ssl/nginx.key
export SENSEPITCH_EDGE_LISTEN_SSL_CERT_PATH=ssl/nginx.crt
export SENSEPITCH_EDGE_SITES_0_KEY=localhost
export SENSEPITCH_EDGE_SITES_0_UPSTREAM_TARGET=$UPSTREAM_IP:80
export SENSEPITCH_EDGE_METRICS_ENABLE=true
export SENSEPITCH_EDGE_PROTECTION_DEFLECTOR_TOKEN_GENERATORS_0_PREFIX=x
export SENSEPITCH_EDGE_PROTECTION_DEFLECTOR_TOKEN_GENERATORS_0_SECRET_VALUE=variousboxes
export SENSEPITCH_EDGE_PROTECTION_DEFLECTOR_BYPASS_URIS_0=/10kb.img

java -XX:+UseZGC --enable-native-access=ALL-UNNAMED -jar "$BENCH/pooled.jar" > "$NAME.log" 2>&1 &
PID=$!
./wait-for-it.sh localhost:17443 --timeout=30 > /dev/null

if [ "$WARMUP_SECONDS" -gt 0 ]; then
  echo "GET https://localhost:17443/10kb.img" \
    | vegeta attack -insecure -duration="${WARMUP_SECONDS}s" -timeout=10s -rate="$WARMUP_RATE" \
      -keepalive=true > /dev/null
fi

{
  echo "rate $RATE, warm-up $WARMUP_SECONDS s at $WARMUP_RATE," \
    "request log disabled: ${SENSEPITCH_EDGE_REQUEST_LOG_DISABLED:-false}," \
    "TIME_WAIT before: $(ss -tan state time-wait | wc -l)"
  echo "GET https://localhost:17443/10kb.img" \
    | vegeta attack -insecure -duration=10s -timeout=10s -rate="$RATE" -keepalive=true \
    | vegeta report
  echo "TIME_WAIT after: $(ss -tan state time-wait | wc -l), by the side that closed first:"
  ss -tan state time-wait | awk 'NR > 1 {
      n = split($3, local, ":"); m = split($4, peer, ":")
      if (peer[m] == "17443") kind = "vegeta closed a connection to the edge"
      else if (local[n] == "17443") kind = "edge closed a client connection"
      else if (peer[m] == "80") kind = "edge closed an upstream connection"
      else kind = "other"
      count[kind]++
    } END { for (kind in count) print "  " count[kind], kind }'
  curl -s http://localhost:9400/metrics | grep -E "^sensepitch_upstream_"
} | tee "$NAME.txt"

kill $PID
wait $PID 2> /dev/null || true
docker compose down > /dev/null 2>&1
