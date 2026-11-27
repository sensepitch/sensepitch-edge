#!/bin/bash
# Compares two sensepitch-edge JARs (upstream on the client's event loop vs. on any loop of the
# group) with the setup of performance-test/start.sh. Rounds alternate the order of the JARs.
set -e
BENCH=$(cd "$(dirname "$0")" && pwd)
PERF=$(cd "$BENCH/../.." && pwd)
OUT=$BENCH/results-300rps
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
  attack $URL $RATE 5s > /dev/null   # warm-up, discarded
  sleep 65                           # let TIME_WAIT of the warm-up drain
  attack $URL $RATE 20s | vegeta report > $OUT/$1-$2.txt
  kill $PID; wait $PID 2>/dev/null || true
  sleep 65
}

RATE=300
N=https://$NGINX_PROXY_IP:443/10kb.img
attack $N $RATE 5s > /dev/null
attack $N $RATE 20s | vegeta report > $OUT/nginx.txt
sleep 65

for round in 1 2 3; do
  if [ $((round % 2)) = 1 ]; then order="separate-loops same-loop"; else order="same-loop separate-loops"; fi
  for jar in $order; do
    echo "round $round $jar"
    run_jar $jar $round
  done
done
docker compose down
echo done
