#!/bin/bash
#
# Compares NGINX and the edge (pooled.jar in this folder, request log on and off) at several rates,
# with the setup of performance-test/start.sh: GET /10kb.img through TLS to the nginx-static
# container. Each step starts a fresh server (the edge JVM, or the NGINX proxy container), warms it
# up for WARMUP_SECONDS at WARMUP_RATE requests per second (report discarded), then sends the rate
# for 10 s and samples the CPU once per second: the machine (mpstat) and the server process(es)
# (pidstat). Before each step it waits until the local ports in TIME_WAIT are gone.
#
# CPU figures: the average of the samples 6 to 8 of the 10, the middle of the measurement, so
# neither the start of the measured vegeta run (its TLS handshakes) nor its end is included.
# "machine busy" is 100 - %idle of all CPUs; "server cores" is the %CPU of the server / 100.
# NGINX is the nginx-proxy container only, its worker processes found via docker; the nginx-static
# upstream is not counted, for neither server.
#
# Usage: ./compare.sh [rates...]     default 5000 10000 20000; about 70 s per step
#        ./compare.sh --summary       rebuilds the summary table from the existing results
# Variables: SERVERS (default "nginx edge-on edge-off"), WARMUP_RATE (1000), WARMUP_SECONDS (10),
#            RESULTS (folder, default results-compare)
#
# Output: results-compare/<server>-<rate>.txt (vegeta report), .mpstat, .pidstat, and the summary
# table in results-compare/summary.md
set -e
RATES=${*:-5000 10000 20000}
WARMUP_RATE=${WARMUP_RATE:-1000}
WARMUP_SECONDS=${WARMUP_SECONDS:-10}
MEASURE_SECONDS=10
BENCH=$(cd "$(dirname "$0")" && pwd)
OUT=$BENCH/${RESULTS:-results-compare}
mkdir -p "$OUT"
cd "$BENCH/../.."

attack() { # url rate seconds
  echo "GET $1" | vegeta attack -insecure -duration="${3}s" -timeout=10s -rate="$2" -keepalive=true
}

wait_for_ports() {
  until [ "$(ss -tan state time-wait | wc -l)" -lt 200 ]; do sleep 3; done
}

# average of the samples 6 to 8 of an mpstat file: 100 - %idle
machine_busy() {
  awk '$3 == "all" && $1 != "Average:" { n++; if (n >= 6 && n <= 8) { sum += 100 - $NF; c++ } }
    END { printf "%.0f", sum / c }' "$1"
}

# average of the samples 6 to 8 of a pidstat file, %CPU of all listed processes per sample, in cores
server_cores() {
  awk 'NF > 3 && $1 != "Average:" && $(NF-2) ~ /^[0-9.]+$/ && $3 ~ /^[0-9]+$/ {
      t = $1; if (!(t in seen)) { seen[t] = ++n; order[n] = t } cpu[t] += $(NF-2)
    } END { for (i = 6; i <= 8; i++) { sum += cpu[order[i]]; c++ } printf "%.1f", sum / c / 100 }' "$1"
}

# vegeta report line values
report_value() { # file label field
  grep "^$2" "$1" | sed -E 's/.*\] +//' | tr ',' '\n' | sed -n "${3}p" | tr -d ' '
}

step() { # server rate log(on|off)
  local server=$1 rate=$2 log=$3 name url pids
  name=$OUT/$server-$rate${log:+-log-$log}
  wait_for_ports
  docker compose up -d > /dev/null 2>&1
  local upstream proxy
  upstream=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' \
    "$(docker compose ps -q nginx-static)")
  proxy=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' \
    "$(docker compose ps -q nginx-proxy)")
  ./wait-for-it.sh "$upstream:80" --timeout=10 > /dev/null
  ./wait-for-it.sh "$proxy:443" --timeout=10 > /dev/null
  local edge=""
  if [ "$server" = edge ]; then
    (
      export SENSEPITCH_EDGE_LISTEN_ADDRESS=0.0.0.0
      export SENSEPITCH_EDGE_LISTEN_HTTPS_PORT=17443
      export SENSEPITCH_EDGE_LISTEN_SSL_KEY_PATH=ssl/nginx.key
      export SENSEPITCH_EDGE_LISTEN_SSL_CERT_PATH=ssl/nginx.crt
      export SENSEPITCH_EDGE_SITES_0_KEY=localhost
      export SENSEPITCH_EDGE_SITES_0_UPSTREAM_TARGET=$upstream:80
      export SENSEPITCH_EDGE_PROTECTION_DEFLECTOR_TOKEN_GENERATORS_0_PREFIX=x
      export SENSEPITCH_EDGE_PROTECTION_DEFLECTOR_TOKEN_GENERATORS_0_SECRET_VALUE=variousboxes
      export SENSEPITCH_EDGE_PROTECTION_DEFLECTOR_BYPASS_URIS_0=/10kb.img
      if [ "$log" = off ]; then export SENSEPITCH_EDGE_REQUEST_LOG_DISABLED=true; fi
      exec java -XX:+UseZGC --enable-native-access=ALL-UNNAMED -jar "$BENCH/pooled.jar" \
        > "$name.log" 2>&1
    ) &
    edge=$!
    ./wait-for-it.sh localhost:17443 --timeout=30 > /dev/null
    url=https://localhost:17443/10kb.img
    pids=$edge
  else
    url=https://$proxy:443/10kb.img
    pids=$(pgrep -d, -P "$(docker inspect -f '{{.State.Pid}}' "$(docker compose ps -q nginx-proxy)")")
  fi
  attack "$url" "$WARMUP_RATE" "$WARMUP_SECONDS" > /dev/null
  mpstat 1 "$MEASURE_SECONDS" > "$name.mpstat" &
  local sampler1=$!
  pidstat -u -p "$pids" 1 "$MEASURE_SECONDS" > "$name.pidstat" &
  local sampler2=$!
  attack "$url" "$rate" "$MEASURE_SECONDS" | vegeta report > "$name.txt"
  wait "$sampler1" "$sampler2"
  if [ -n "$edge" ]; then
    kill "$edge"
    wait "$edge" 2> /dev/null || true
  fi
  docker compose down > /dev/null 2>&1
  summary_row "$name" "$rate" "$server" "${log:--}" | tee -a "$OUT/summary.md"
}

# one row of the summary table, from the files of a step; the sent rate and the throughput are
# what vegeta achieved, below the requested rate when the machine cannot keep up
summary_row() { # name rate server log
  local name=$1
  printf "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |\n" \
    "$2" "$(report_value "$name.txt" Requests 2 | cut -d. -f1)" \
    "$(report_value "$name.txt" Requests 3 | cut -d. -f1)" "$3" "$4" \
    "$(grep '^Success' "$name.txt" | awk '{print $NF}')" \
    "$(report_value "$name.txt" Latencies 3)" \
    "$(report_value "$name.txt" Latencies 4)" \
    "$(report_value "$name.txt" Latencies 6)" \
    "$(machine_busy "$name.mpstat") %" \
    "$(server_cores "$name.pidstat")"
}

HEADER="| Rate | Sent | Throughput | Server | Request log | Success | p50 | p90 | p99 | Machine busy |"
HEADER="$HEADER Server cores |"
SEPARATOR="| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"
if [ "$1" = --summary ]; then
  # rebuilds the summary table from the files of an earlier run
  echo "$HEADER"; echo "$SEPARATOR"
  for f in "$OUT"/*.txt; do
    base=$(basename "$f" .txt); server=${base%%-*}; rest=${base#*-}; rate=${rest%%-*}
    log=-; case $base in *-log-on) log=on ;; *-log-off) log=off ;; esac
    echo "$rate $server $log ${f%.txt}"
  done | sort -n -k1,1 -k2,2r -k3,3r | while read -r rate server log name; do
    summary_row "$name" "$rate" "$server" "$log"
  done | tee "$OUT/summary.md"
  exit 0
fi
echo "$HEADER" | tee "$OUT/summary.md"
echo "$SEPARATOR" | tee -a "$OUT/summary.md"
for rate in $RATES; do
  for server in ${SERVERS:-nginx edge-on edge-off}; do
    case $server in
      nginx) step nginx "$rate" "" ;;
      edge-on) step edge "$rate" on ;;
      edge-off) step edge "$rate" off ;;
    esac
  done
done
