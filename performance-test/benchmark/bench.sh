#!/usr/bin/env bash
# Runs the benchmark: NGINX, HAProxy and the edge, see README.md.
#
# Usage: BENCH_MODE=smoke|quick|diligent performance-test/benchmark/bench.sh
#        (default quick). Build the edge first: ./mvnw package -DskipTests
# Output: performance-test/runs/<date>-benchmark-<mode>/ with summary.md, run.om, metrics/, raw/
set -euo pipefail
BENCH_DIR=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=lib.sh
source "$BENCH_DIR/lib.sh"
REPO_DIR=$(cd "$PERF_DIR/.." && pwd)

for tool in knockout.sh shb docker curl python3 ss java unzip openssl; do
  command -v "$tool" > /dev/null || { echo "missing tool: $tool" >&2; exit 1; }
done
# the knockout of 2026-11-28 takes positional paths and --junit-report
usage=$(knockout.sh run --help 2>&1 || true)
grep -q -- --junit-report <<< "$usage" \
  || { echo "knockout.sh is too old, --junit-report is needed" >&2; exit 1; }

BENCH_EDGE_JAR=$(ls "$REPO_DIR"/target/sensepitch-edge-*-with-dependencies.jar 2> /dev/null | head -1)
[ -n "$BENCH_EDGE_JAR" ] || { echo "no edge JAR, build it: ./mvnw package -DskipTests" >&2; exit 1; }
BENCH_EDGE_VERSION=$(unzip -p "$BENCH_EDGE_JAR" META-INF/MANIFEST.MF \
  | sed -n 's/^Implementation-Version: //p' | tr -d '\r')
BENCH_COMMIT=$(git -C "$REPO_DIR" rev-parse --short HEAD)
if [ -n "$(git -C "$REPO_DIR" status --porcelain)" ]; then BENCH_COMMIT="$BENCH_COMMIT-dirty"; fi
BENCH_RUN_DIR=$PERF_DIR/runs/$(date +%Y-%m-%d-%H%M%S)-benchmark-$BENCH_MODE
mkdir -p "$BENCH_RUN_DIR"

# the generated files, as performance-test/start.sh does; they exist before docker compose up,
# otherwise docker creates directories in their place
mkdir -p "$PERF_DIR/ssl" "$PERF_DIR/html"
if [ ! -f "$PERF_DIR/ssl/nginx.crt" ]; then
  openssl req -x509 -nodes -days 365 -newkey rsa:2048 -keyout "$PERF_DIR/ssl/nginx.key" \
    -out "$PERF_DIR/ssl/nginx.crt" -subj "/CN=localhost" 2> /dev/null
fi
: > "$PERF_DIR/html/0b.img"
head -c 512 /dev/zero > "$PERF_DIR/html/512b.img"
head -c 10240 /dev/zero > "$PERF_DIR/html/10kb.img"

# the machine and the versions, once per run
"${COMPOSE[@]}" pull -q > /dev/null 2>&1 || true
python3 - "$BENCH_RUN_DIR/host.json" << PYEOF
import json, os, platform, subprocess, sys
def run(*cmd):
    done = subprocess.run(cmd, capture_output=True, text=True)
    return done.stdout + done.stderr
cpu_model = next(l.split(":", 1)[1].strip() for l in open("/proc/cpuinfo") if l.startswith("model name"))
os_name = next((l.split("=", 1)[1].strip().strip('"') for l in open("/etc/os-release")
                if l.startswith("PRETTY_NAME=")), "")
mem = next(int(l.split()[1]) * 1024 for l in open("/proc/meminfo") if l.startswith("MemTotal:"))
try:
    max_hz = int(open("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq").read()) * 1000
except OSError:
    max_hz = 0
java = run("java", "-version").splitlines()[0]
nginx = run("docker", "run", "--rm", "nginx:1.29.0-alpine", "nginx", "-v").strip().splitlines()[-1].split("/")[-1]
haproxy = run("docker", "run", "--rm", "haproxy:3.2.27-alpine", "haproxy", "-v").split()[2]
shb = os.path.realpath(subprocess.run(["sh", "-c", "command -v shb"], capture_output=True, text=True).stdout.strip())
shb_version = subprocess.run(["sha256sum", shb], capture_output=True, text=True).stdout.split()[0][:12]
json.dump({
    "info": {"cpu_model": cpu_model, "cpu_cores": str(os.cpu_count()), "kernel": platform.release(),
             "os": os_name, "java_version": java, "nginx_version": nginx,
             "haproxy_version": haproxy, "shb_version": "sha256:" + shb_version},
    "cpu_cores": os.cpu_count(), "memory_bytes": mem, "cpu_max_hertz": max_hz,
}, open(sys.argv[1], "w"), indent=2)
PYEOF

"${COMPOSE[@]}" up -d nginx-static > "$BENCH_RUN_DIR/compose.log" 2>&1
BENCH_UPSTREAM=$(container_ip nginx-static)
wait_for_port "$BENCH_UPSTREAM" 80 30
cleanup() { "${COMPOSE[@]}" down > /dev/null 2>&1 || true; }
trap cleanup EXIT

export BENCH_MODE BENCH_RUN_DIR BENCH_UPSTREAM BENCH_EDGE_JAR BENCH_EDGE_VERSION BENCH_COMMIT
echo "benchmark $BENCH_MODE, edge $BENCH_EDGE_VERSION, results in $BENCH_RUN_DIR"
cd "$BENCH_DIR"
status=0
knockout.sh run --junit-report "$BENCH_RUN_DIR/junit.xml" knockout-tests || status=$?
echo "summary: $BENCH_RUN_DIR/summary.md"
exit "$status"
