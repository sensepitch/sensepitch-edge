#!/bin/bash
# Saturating load against the edge with the upstream connection pool (pooled.jar, default
# configuration) and against NGINX, with the setup of performance-test/start.sh.
set -e
BENCH=$(cd "$(dirname "$0")" && pwd)
PERF=$(cd "$BENCH/../.." && pwd)
OUT=$BENCH/results-saturating
mkdir -p $OUT
cd $PERF

docker compose up -d
ip() { docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$(docker compose ps -q $1)"; }
UPSTREAM_IP=$(ip nginx-static)
NGINX_PROXY_IP=$(ip nginx-proxy)
./wait-for-it.sh $UPSTREAM_IP:80 --timeout=10
./wait-for-it.sh $NGINX_PROXY_IP:443 --timeout=10

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

URL=https://localhost:17443/10kb.img
attack() { # url rate duration
  echo "GET $1" | vegeta attack -insecure -duration=$3 -timeout=10s -rate=$2 -keepalive=true
}

run_jar() { # name round
  java -XX:+UseZGC --enable-native-access=ALL-UNNAMED -jar $BENCH/$1.jar > $OUT/$1-$2.log 2>&1 &
  PID=$!
  ./wait-for-it.sh localhost:17443 --timeout=30 > /dev/null
  attack $URL 10000 10s > /dev/null   # warm-up, discarded
  attack $URL 50000 10s | vegeta report > $OUT/$1-$2-50k.txt
  sleep 2
  attack $URL 10000 10s | vegeta report > $OUT/$1-$2-10k.txt
  kill $PID; wait $PID 2>/dev/null || true
  sleep 2
}

N=https://$NGINX_PROXY_IP:443/10kb.img
attack $N 10000 10s > /dev/null
attack $N 50000 10s | vegeta report > $OUT/nginx-50k.txt
sleep 2
attack $N 10000 10s | vegeta report > $OUT/nginx-10k.txt

for round in 1 2 3; do
  echo "round $round pooled"
  run_jar pooled $round
done
docker compose down
echo done
