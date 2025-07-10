#!/bin/bash

printContainerIp() {
docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $1
}

mkdir -p ssl
openssl req -x509 -nodes -days 365 \
  -newkey rsa:2048 \
  -keyout ssl/nginx.key \
  -out ssl/nginx.crt \
  -subj "/CN=localhost"


dd if=/dev/zero bs=1d 024 count=100 of=html/100kb.img
dd if=/dev/zero bs=1024 count=10 of=html/10kb.img

docker compose up -d

DIRNAME=$(basename `pwd`)

UPSTREAM_IP=$(printContainerIp $DIRNAME-nginx-static-1)
UPSTREAM_PORT=80
NGINX_PROXY_IP=$(printContainerIp $DIRNAME-nginx-proxy-1)
NGINX_PROXY_PORT=443

chmod u+x ./wait-for-it.sh
./wait-for-it.sh $UPSTREAM_IP:$UPSTREAM_PORT --timeout=10
./wait-for-it.sh $NGINX_PROXY_IP:$NGINX_PROXY_PORT --timeout=10

SENSEPITCH_EDGE_LISTEN_HTTPS=true
SENSEPITCH_EDGE_LISTEN_PORT=7443
SENSEPITCH_EDGE_LISTEN_SSL_KEY=ssl/nginx.key
SENSEPITCH_EDGE_LISTEN_SSL_CERT=ssl/nginx.crt
SENSEPITCH_EDGE_ADMISSION_BYPASS_URI_PREFIXES=/
SENSEPITCH_EDGE_ADMISSION_TOKEN_GENERATOR_0_PREFIX=X
SENSEPITCH_EDGE_ADMISSION_TOKEN_GENERATOR_0_SECRET=dummy
SENSEPITCH_EDGE_UPSTREAM_0_TARGET=$UPSTREAM_IP:$UPSTREAM_PORT
export ${!SENSEPITCH_@}

java -jar ../target/sensepitch-edge-1.0-SNAPSHOT-with-dependencies.jar > sensepitch-edge.log 2>&1 &

echo "GET https://$NGINX_PROXY_IP:$NGINX_PROXY_PORT/10kb.img" | vegeta attack -insecure -duration=5s -timeout=10s -rate=100 -keepalive=true | vegeta report
echo "GET https://localhost:7443/10kb.img" | vegeta attack -insecure -duration=5s -timeout=10s -rate=10000 -keepalive=true | vegeta report

