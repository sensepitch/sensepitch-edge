# shellcheck shell=bash
# Shared functions of the benchmark, sourced by bench.sh and the knockout tests. See README.md.
#
# Settings from the environment, exported by bench.sh: BENCH_MODE, BENCH_RUN_DIR,
# BENCH_UPSTREAM (address of nginx-static), BENCH_EDGE_JAR, BENCH_COMMIT, BENCH_EDGE_VERSION.

BENCH_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PERF_DIR=$(cd "$BENCH_DIR/.." && pwd)
BENCH_MODE=${BENCH_MODE:-quick}
EDGE_PORT=17443
EDGE_METRICS_PORT=9400
# TIME_WAIT sockets below which a step starts, as in the 2026-11-27 runs
TIME_WAIT_LIMIT=200
COMPOSE=(docker compose -f "$PERF_DIR/docker-compose.yml")

# mode settings: the matrix and the times in seconds
case $BENCH_MODE in
  smoke)
    PROXIES="nginx haproxy edge" SIZES="10kb" LOGS="on" CONNECTIONS="10" REPEATS=1
    WARMUP_EDGE=1 WARMUP_NATIVE=1 MEASURE=1 ;;
  quick)
    PROXIES="nginx haproxy edge" SIZES="0b 512b 10kb" LOGS="on off" CONNECTIONS="10 100 1000"
    REPEATS=1 WARMUP_EDGE=5 WARMUP_NATIVE=1 MEASURE=5 ;;
  diligent)
    PROXIES="nginx haproxy edge" SIZES="0b 512b 10kb" LOGS="on off" CONNECTIONS="10 100 1000"
    REPEATS=3 WARMUP_EDGE=10 WARMUP_NATIVE=10 MEASURE=10 ;;
  *)
    echo "unknown BENCH_MODE $BENCH_MODE, expected smoke, quick or diligent" >&2
    exit 1 ;;
esac

# the steps of the mode, one per line: proxy size log connections repeat; the order varies the
# proxy fastest, so the three proxies of a variant are measured close in time
bench_matrix() {
  local repeat size conns log proxy
  for repeat in $(seq 1 "$REPEATS"); do
    for size in $SIZES; do
      for conns in $CONNECTIONS; do
        for log in $LOGS; do
          for proxy in $PROXIES; do
            echo "$proxy $size $log $conns $repeat"
          done
        done
      done
    done
  done
}

container_ip() { # service
  docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' \
    "$("${COMPOSE[@]}" ps -q "$1")"
}

wait_for_port() { # host port seconds
  local deadline=$((SECONDS + $3))
  until (exec 3<> "/dev/tcp/$1/$2") 2> /dev/null; do
    if ((SECONDS >= deadline)); then
      return 1
    fi
    sleep 0.2
  done
}

time_wait_sockets() {
  ss -tan state time-wait | tail -n +2 | wc -l
}

# starts the proxy of a step, sets PROXY_URL and PROXY_PIDS (the processes whose CPU is
# measured); the log of the proxy goes to $1.log
start_proxy() { # raw-prefix proxy log
  local raw=$1 proxy=$2 log=$3
  case $proxy in
    edge)
      (
        export SENSEPITCH_EDGE_LISTEN_ADDRESS=127.0.0.1
        export SENSEPITCH_EDGE_LISTEN_HTTPS_PORT=$EDGE_PORT
        export SENSEPITCH_EDGE_LISTEN_SSL_KEY_PATH=$PERF_DIR/ssl/nginx.key
        export SENSEPITCH_EDGE_LISTEN_SSL_CERT_PATH=$PERF_DIR/ssl/nginx.crt
        export SENSEPITCH_EDGE_SITES_0_KEY=localhost
        export SENSEPITCH_EDGE_SITES_0_UPSTREAM_TARGET=$BENCH_UPSTREAM:80
        export SENSEPITCH_EDGE_PROTECTION_DEFLECTOR_TOKEN_GENERATORS_0_PREFIX=x
        export SENSEPITCH_EDGE_PROTECTION_DEFLECTOR_TOKEN_GENERATORS_0_SECRET_VALUE=variousboxes
        export SENSEPITCH_EDGE_PROTECTION_DEFLECTOR_BYPASS_URIS_0=/0b.img
        export SENSEPITCH_EDGE_PROTECTION_DEFLECTOR_BYPASS_URIS_1=/512b.img
        export SENSEPITCH_EDGE_PROTECTION_DEFLECTOR_BYPASS_URIS_2=/10kb.img
        if [ "$log" = off ]; then export SENSEPITCH_EDGE_REQUEST_LOG_DISABLED=true; fi
        exec java -XX:+UseZGC --enable-native-access=ALL-UNNAMED -jar "$BENCH_EDGE_JAR"
      ) > "$raw.out" 2>&1 &
      PROXY_PIDS=$!
      wait_for_port 127.0.0.1 "$EDGE_PORT" 60 || return 1
      # IPv4 like the proxies in their containers; shb does not fall back from ::1 for
      # localhost. The Host header names the site the edge serves
      PROXY_URL=https://127.0.0.1:$EDGE_PORT
      PROXY_HOST=(-H "Host: localhost") ;;
    nginx | haproxy)
      local service=nginx-proxy env=()
      if [ "$proxy" = nginx ]; then
        [ "$log" = off ] && env=(NGINX_PROXY_ACCESS_LOG=off)
      else
        service=haproxy
        env=("HAPROXY_ACCESS_LOG=$log")
      fi
      env "${env[@]}" "${COMPOSE[@]}" up -d --force-recreate "$service" > "$raw.compose" 2>&1 \
        || return 1
      local ip
      ip=$(container_ip "$service")
      wait_for_port "$ip" 443 30 || return 1
      PROXY_PIDS=$(docker inspect -f '{{.State.Pid}}' "$("${COMPOSE[@]}" ps -q "$service")")
      PROXY_URL=https://$ip
      PROXY_HOST=() ;;
  esac
}

# Stops the proxy and keeps the start and the end of its output in $1.log. The proxies write
# their whole log during the measurement, as in production; kept in full, the request logs of a
# quick run took 5.9 GB.
stop_proxy() { # raw-prefix proxy
  local service
  case $2 in
    edge)
      kill "$PROXY_PIDS" 2> /dev/null || true
      wait "$PROXY_PIDS" 2> /dev/null || true ;;
    nginx | haproxy)
      service=haproxy
      [ "$2" = nginx ] && service=nginx-proxy
      "${COMPOSE[@]}" logs --no-log-prefix "$service" > "$1.out" 2>&1
      "${COMPOSE[@]}" rm -sf "$service" > /dev/null 2>&1 ;;
  esac
  if [ -f "$1.out" ]; then
    { head -n 50 "$1.out"; echo "[... $(wc -l < "$1.out") lines in total ...]"; tail -n 50 "$1.out"; } \
      > "$1.log"
    rm -f "$1.out"
  fi
}

# one step: fresh proxy, warm-up, measurement with CPU samples, edge scrapes; keeps the raw
# output only, see benchtool.py export
bench_step() { # proxy size log connections repeat
  local proxy=$1 size=$2 log=$3 conns=$4 repeat=$5
  local step="$proxy-$size-log-$log-c$conns-r$repeat"
  local raw="$BENCH_RUN_DIR/raw/$step"
  mkdir -p "$BENCH_RUN_DIR/raw"
  local step_start time_wait warmup=$WARMUP_NATIVE
  step_start=$(date +%s.%N)
  until [ "$(time_wait_sockets)" -lt "$TIME_WAIT_LIMIT" ]; do sleep 1; done
  time_wait=$(time_wait_sockets)
  [ "$proxy" = edge ] && warmup=$WARMUP_EDGE
  if ! start_proxy "$raw" "$proxy" "$log"; then
    stop_proxy "$raw" "$proxy"
    echo "proxy $proxy did not start, see $raw.log" >&2
    return 1
  fi
  local url="$PROXY_URL/$size.img"
  shb -c "$conns" -z "${warmup}s" "${PROXY_HOST[@]}" "$url" > "$raw.warmup" 2>&1 || true
  local edge_before="" edge_after=""
  if [ "$proxy" = edge ]; then
    edge_before=$(date +%s.%N)
    scrape_edge > "$raw.edge-before.om"
  fi
  local measure_start sampler
  measure_start=$(date +%s.%N)
  python3 "$BENCH_DIR/benchtool.py" sample "$raw.samples.jsonl" "$MEASURE" $PROXY_PIDS &
  sampler=$!
  shb -c "$conns" -z "${MEASURE}s" -j "${PROXY_HOST[@]}" "$url" > "$raw.shb.json" 2> "$raw.shb.err"
  local shb_status=$?
  local measure_end
  measure_end=$(date +%s.%N)
  wait "$sampler"
  if [ "$proxy" = edge ]; then
    edge_after=$(date +%s.%N)
    scrape_edge > "$raw.edge-after.om"
  fi
  stop_proxy "$raw" "$proxy"
  if [ "$shb_status" -ne 0 ] || [ ! -s "$raw.shb.json" ]; then
    echo "shb failed with status $shb_status, see $raw.shb.err" >&2
    return 1
  fi
  write_meta "$raw.meta.json" "$proxy" "$size" "$log" "$conns" "$repeat" "$step_start" \
    "$measure_start" "$measure_end" "$time_wait" "$edge_before" "$edge_after"
  python3 "$BENCH_DIR/benchtool.py" describe "$raw.meta.json" "$raw"
}

# the edge's own metrics, as OpenMetrics
scrape_edge() {
  curl -fsS -H 'Accept: application/openmetrics-text;version=1.0.0' \
    "http://127.0.0.1:$EDGE_METRICS_PORT/metrics"
}

write_meta() { # file proxy size log conns repeat step_start measure_start measure_end time_wait edge_before edge_after
  python3 - "$@" << 'EOF'
import json, sys
(path, proxy, size, log, conns, repeat, step_start, measure_start, measure_end, time_wait,
 edge_before, edge_after) = sys.argv[1:]
import os
host = json.load(open(os.environ["BENCH_RUN_DIR"] + "/host.json"))
labels = {
    "bench_run": os.path.basename(os.environ["BENCH_RUN_DIR"]),
    "bench_mode": os.environ["BENCH_MODE"],
    "bench_proxy": proxy, "bench_size": size, "bench_log": log,
    "bench_connections": conns, "bench_repeat": repeat,
    "bench_commit": os.environ["BENCH_COMMIT"],
    "bench_edge_version": os.environ["BENCH_EDGE_VERSION"],
}
meta = {
    "labels": labels, "host": host,
    "step_start": float(step_start), "measure_start": float(measure_start),
    "measure_end": float(measure_end), "time_wait_sockets": int(time_wait),
}
if edge_before:
    meta["edge_before"] = float(edge_before)
    meta["edge_after"] = float(edge_after)
json.dump(meta, open(path, "w"), indent=2)
EOF
}
