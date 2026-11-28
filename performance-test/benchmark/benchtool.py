#!/usr/bin/env python3
"""Tools of the benchmark, standard library only. See README.md.

Commands:
  sample OUT SECONDS PID...   sample the machine and the processes PID (with their threads and
                              child processes) once per second, as JSON lines into OUT
  describe META RAW_PREFIX    print the one-line result of a step, for knockout
  summary RUN_DIR             write RUN_DIR/summary.md from the raw files of the steps
  export RUN_DIR [OUT]        convert the raw files of all steps, with the edge's own metrics,
                              into one OpenMetrics file, default RUN_DIR/run.om
  check FILE...               check that files are OpenMetrics text as this tool writes it

The steps keep only their raw output; the OpenMetrics file is made on demand by export, e.g.
before an upload, since converting every step while measuring cost time and disk.
"""

import json
import os
import re
import statistics
import sys
import time

CLK_TCK = os.sysconf("SC_CLK_TCK")

# ---------------------------------------------------------------------------------------------
# sampling


def _cpu_times():
    """busy and total jiffies of all CPUs, from /proc/stat"""
    with open("/proc/stat") as f:
        fields = [int(x) for x in f.readline().split()[1:]]
    idle = fields[3] + fields[4]  # idle + iowait
    total = sum(fields[:8])  # without guest, which is counted in user already
    return total - idle, total


def _descendants(pids):
    """the processes and all their child processes, from /proc/*/stat"""
    children = {}
    for entry in os.listdir("/proc"):
        if entry.isdigit():
            try:
                with open(f"/proc/{entry}/stat") as f:
                    ppid = int(f.read().rsplit(")", 1)[1].split()[1])
                children.setdefault(ppid, []).append(int(entry))
            except (OSError, IndexError, ValueError):
                pass
    result, todo = set(), list(pids)
    while todo:
        pid = todo.pop()
        if pid not in result:
            result.add(pid)
            todo.extend(children.get(pid, []))
    return result


def _process_jiffies(pids):
    """user and system jiffies of the processes, including all their threads"""
    total = 0
    for pid in pids:
        try:
            with open(f"/proc/{pid}/stat") as f:
                fields = f.read().rsplit(")", 1)[1].split()
            total += int(fields[11]) + int(fields[12])
        except (OSError, IndexError, ValueError):
            pass  # ended meanwhile
    return total


def _meminfo(key):
    with open("/proc/meminfo") as f:
        for line in f:
            if line.startswith(key + ":"):
                return int(line.split()[1]) * 1024
    return 0


def sample(out, seconds, pids):
    pids = _descendants([int(p) for p in pids])
    busy0, total0 = _cpu_times()
    proc0 = _process_jiffies(pids)
    t0 = time.time()
    with open(out, "w") as f:
        for i in range(1, int(seconds) + 1):
            time.sleep(max(0.0, t0 + i - time.time()))
            now = time.time()
            busy1, total1 = _cpu_times()
            proc1 = _process_jiffies(pids)
            with open("/proc/loadavg") as la:
                load1 = float(la.read().split()[0])
            f.write(
                json.dumps(
                    {
                        "ts": now,
                        "machine_busy": (busy1 - busy0) / max(1, total1 - total0),
                        "proxy_cores": (proc1 - proc0) / CLK_TCK / (now - t0 - (i - 1)),
                        "mem_available": _meminfo("MemAvailable"),
                        "load1": load1,
                    }
                )
                + "\n"
            )
            f.flush()
            busy0, total0, proc0 = busy1, total1, proc1


# ---------------------------------------------------------------------------------------------
# OpenMetrics


def _escape(value):
    return str(value).replace("\\", "\\\\").replace("\n", "\\n").replace('"', '\\"')


def _labels(labels):
    if not labels:
        return ""
    return "{" + ",".join(f'{k}="{_escape(v)}"' for k, v in labels.items()) + "}"


def _number(value):
    if isinstance(value, float):
        return repr(value)
    return str(value)


class Family:
    """one metric family: metadata and samples as (sample name, labels, value, timestamp)"""

    def __init__(self, name, type_, help_="", unit=""):
        self.name, self.type, self.help, self.unit = name, type_, help_, unit
        self.samples = []

    def add(self, sample_name, labels, value, ts):
        self.samples.append((sample_name, dict(labels), value, ts))


def _sample_line(sample_name, labels, value, ts):
    return f"{sample_name}{_labels(labels)} {_number(value)} {ts:.3f}"


def write_families(families, out):
    """writes the families as OpenMetrics, the points of a series in increasing time order"""
    with open(out, "w") as f:
        for fam in families.values():
            if fam.type:
                f.write(f"# TYPE {fam.name} {fam.type}\n")
            if fam.unit:
                f.write(f"# UNIT {fam.name} {fam.unit}\n")
            if fam.help:
                f.write(f"# HELP {fam.name} {_escape(fam.help)}\n")
            rows = sorted(
                fam.samples,
                key=lambda s: (sorted(s[1].items()), s[0], s[3]),
            )
            for row in rows:
                f.write(_sample_line(*row) + "\n")
        f.write("# EOF\n")


_SAMPLE = re.compile(r"^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{(.*)\})?\s+(\S+)(\s+(\S+))?\s*$")
_LABEL = re.compile(r'\s*([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"\s*,?')


def _parse_labels(text):
    labels, pos = {}, 0
    while pos < len(text):
        m = _LABEL.match(text, pos)
        if not m:
            raise ValueError("bad labels: " + text)
        labels[m.group(1)] = (
            m.group(2).replace('\\"', '"').replace("\\n", "\n").replace("\\\\", "\\")
        )
        pos = m.end()
    return labels


def _family_of(sample_name, families):
    """the family a sample belongs to: its name, or its name without an OpenMetrics suffix"""
    if sample_name in families:
        return families[sample_name]
    for suffix in ("_total", "_created", "_count", "_sum", "_bucket", "_info", "_gcount", "_gsum"):
        if sample_name.endswith(suffix) and sample_name[: -len(suffix)] in families:
            return families[sample_name[: -len(suffix)]]
    return None


def read_openmetrics(text, families, extra_labels=None, default_ts=None):
    """adds the samples of an OpenMetrics text to families, with extra labels and a timestamp
    for samples without one"""
    for line in text.splitlines():
        if not line or line == "# EOF":
            continue
        if line.startswith("# "):
            parts = line.split(" ", 3)
            kind, name = parts[1], parts[2]
            rest = parts[3] if len(parts) > 3 else ""
            fam = families.get(name)
            if fam is None:
                fam = families[name] = Family(name, "")
            if kind == "TYPE":
                fam.type = rest
            elif kind == "HELP":
                fam.help = rest.replace("\\n", "\n").replace('\\"', '"').replace("\\\\", "\\")
            elif kind == "UNIT":
                fam.unit = rest
            continue
        m = _SAMPLE.match(line)
        if not m:
            raise ValueError("not a sample: " + line)
        sample_name, labels = m.group(1), _parse_labels(m.group(3) or "")
        value = m.group(4)
        ts = float(m.group(6)) if m.group(6) else default_ts
        if ts is None:
            raise ValueError("sample without a timestamp: " + line)
        if extra_labels:
            labels = {**extra_labels, **labels}
        fam = _family_of(sample_name, families)
        if fam is None:
            fam = families[sample_name] = Family(sample_name, "unknown")
        number = float(value) if value not in ("+Inf", "-Inf", "NaN") else value
        if isinstance(number, float) and number.is_integer() and "." not in value and "e" not in value:
            number = int(number)
        fam.add(sample_name, labels, number, ts)


def check(files):
    for path in files:
        lines = open(path).read().splitlines()
        if not lines or lines[-1] != "# EOF":
            raise SystemExit(f"{path}: does not end with # EOF")
        seen = set()
        for line in lines[:-1]:
            if line.startswith("# "):
                kind, name = line.split(" ", 3)[1:3]
                if kind == "TYPE":
                    if name in seen:
                        raise SystemExit(f"{path}: family {name} twice")
                    seen.add(name)
                continue
            m = _SAMPLE.match(line)
            if not m or not m.group(6):
                raise SystemExit(f"{path}: not a sample with a timestamp: {line}")
            _parse_labels(m.group(3) or "")


# ---------------------------------------------------------------------------------------------
# one step


def _gauge(families, name, help_, unit=""):
    return families.setdefault(name, Family(name, "gauge", help_, unit))


def step_families(meta_path, raw, families, with_edge):
    """adds the metrics of one step to families, the edge's own metrics only with_edge, and
    returns the one-line result"""
    with open(meta_path) as f:
        meta = json.load(f)
    labels = meta["labels"]

    with open(raw + ".shb.json") as f:
        shb = json.load(f)
    end = meta["measure_end"]
    if not shb["requests"]["ok"]:
        raise SystemExit(
            f"no request succeeded ({shb['requests']['errors']} errors, "
            f"{shb['requests']['connectErrors']} of them connect errors), see {raw}.warmup"
        )
    ok_codes = sum(n for code, n in shb["statusCodes"].items() if code.startswith("2"))
    other_codes = sum(shb["statusCodes"].values()) - ok_codes
    errors = shb["requests"]["errors"] + shb["requests"]["connectErrors"] + other_codes

    _gauge(families, "bench_requests_per_second", "Requests answered per second, by shb").add(
        "bench_requests_per_second", labels, shb["requestsPerSec"], end
    )
    fam = families.setdefault(
        "bench_requests", Family("bench_requests", "counter", "Requests sent by shb")
    )
    fam.add("bench_requests_total", labels, shb["requests"]["total"], end)
    fam = families.setdefault(
        "bench_errors",
        Family("bench_errors", "counter", "Requests without a 2xx answer: errors and other codes"),
    )
    fam.add("bench_errors_total", labels, errors, end)
    fam = families.setdefault(
        "bench_responses",
        Family("bench_responses", "counter", "Responses by status code"),
    )
    for code, n in shb["statusCodes"].items():
        fam.add("bench_responses_total", {**labels, "code": code}, n, end)
    fam = families.setdefault(
        "bench_latency_seconds",
        Family("bench_latency_seconds", "summary", "Request latency seen by shb", "seconds"),
    )
    for quantile, key in (
        ("0.5", "p50"),
        ("0.9", "p90"),
        ("0.99", "p99"),
        ("0.999", "p99.9"),
        ("0.9999", "p99.99"),
    ):
        fam.add(
            "bench_latency_seconds",
            {**labels, "quantile": quantile},
            shb["latencySeconds"]["percentiles"][key],
            end,
        )
    fam.add("bench_latency_seconds_count", labels, shb["requests"]["total"], end)
    fam.add(
        "bench_latency_seconds_sum",
        labels,
        shb["latencySeconds"]["mean"] * shb["requests"]["total"],
        end,
    )
    _gauge(families, "bench_duration_seconds", "Measured time", "seconds").add(
        "bench_duration_seconds", labels, shb["durationSeconds"], end
    )

    samples = []
    with open(raw + ".samples.jsonl") as f:
        samples = [json.loads(line) for line in f if line.strip()]
    for s in samples:
        _gauge(families, "bench_machine_cpu_busy_ratio", "CPU busy share of the machine, per second").add(
            "bench_machine_cpu_busy_ratio", labels, s["machine_busy"], s["ts"]
        )
        _gauge(families, "bench_proxy_cpu_cores_sample", "CPU of the proxy processes in cores, per second").add(
            "bench_proxy_cpu_cores_sample", labels, s["proxy_cores"], s["ts"]
        )
        _gauge(families, "bench_machine_memory_available_bytes", "MemAvailable of the machine", "bytes").add(
            "bench_machine_memory_available_bytes", labels, s["mem_available"], s["ts"]
        )
        _gauge(families, "bench_machine_load1", "Load average over one minute").add(
            "bench_machine_load1", labels, s["load1"], s["ts"]
        )
    middle = _middle(samples)
    busy = statistics.mean(s["machine_busy"] for s in middle) if middle else 0.0
    cores = statistics.mean(s["proxy_cores"] for s in middle) if middle else 0.0
    _gauge(families, "bench_machine_busy_ratio", "CPU busy share of the machine, middle of the measurement").add(
        "bench_machine_busy_ratio", labels, busy, end
    )
    _gauge(families, "bench_proxy_cpu_cores", "CPU of the proxy in cores, middle of the measurement").add(
        "bench_proxy_cpu_cores", labels, cores, end
    )
    rps = shb["requestsPerSec"]
    _gauge(families, "bench_proxy_cpu_cores_per_10k_rps", "Proxy cores per 10 000 requests per second").add(
        "bench_proxy_cpu_cores_per_10k_rps", labels, cores / rps * 10000 if rps else 0.0, end
    )

    host = meta["host"]
    start = meta["measure_start"]
    fam = families.setdefault(
        "bench_host", Family("bench_host", "info", "The machine and the versions of the tools")
    )
    fam.add("bench_host_info", {**labels, **host["info"]}, 1, start)
    _gauge(families, "bench_machine_cpu_cores", "CPU cores of the machine").add(
        "bench_machine_cpu_cores", labels, host["cpu_cores"], start
    )
    _gauge(families, "bench_machine_memory_bytes", "MemTotal of the machine", "bytes").add(
        "bench_machine_memory_bytes", labels, host["memory_bytes"], start
    )
    if host.get("cpu_max_hertz"):
        _gauge(families, "bench_machine_cpu_max_frequency_hertz", "Maximum CPU frequency", "hertz").add(
            "bench_machine_cpu_max_frequency_hertz", labels, host["cpu_max_hertz"], start
        )
    _gauge(families, "bench_machine_time_wait_sockets", "TCP sockets in TIME_WAIT before the step").add(
        "bench_machine_time_wait_sockets", labels, meta["time_wait_sockets"], meta["step_start"]
    )

    if with_edge:
        for suffix, ts_key in (
            (".edge-before.om", "edge_before"),
            (".edge-after.om", "edge_after"),
        ):
            if os.path.exists(raw + suffix):
                with open(raw + suffix) as f:
                    read_openmetrics(f.read(), families, labels, meta[ts_key])
    return (
        f"# {rps:.0f} req/s, p99 {shb['latencySeconds']['percentiles']['p99'] * 1000:.2f} ms, "
        f"{cores:.1f} cores{f', {errors} errors' if errors else ''}"
    )


def _steps(run_dir):
    """the raw prefixes of the steps of a run, by their meta files"""
    raw_dir = os.path.join(run_dir, "raw")
    return sorted(
        os.path.join(raw_dir, name[: -len(".meta.json")])
        for name in os.listdir(raw_dir)
        if name.endswith(".meta.json")
    )


def describe(meta_path, raw):
    print(step_families(meta_path, raw, {}, with_edge=False))


def export(run_dir, out=None):
    families = {}
    steps = _steps(run_dir)
    for raw in steps:
        step_families(raw + ".meta.json", raw, families, with_edge=True)
    out = out or os.path.join(run_dir, "run.om")
    write_families(families, out)
    check([out])
    print(f"{out}: {len(steps)} steps")


def _middle(samples):
    """the middle of the measurement, without the first and the last fifth (TLS handshakes,
    the end of the run), at least one sample"""
    n = len(samples)
    if n <= 2:
        return samples
    cut = max(1, n // 5)
    return samples[cut : n - cut]


# ---------------------------------------------------------------------------------------------
# summary


def _values(families, name):
    """{variant key: [value per repeat]} of a bench metric, without the repeat label"""
    result = {}
    fam = families.get(name)
    if fam is None:
        return result
    for sample_name, labels, value, _ in fam.samples:
        if sample_name != name:
            continue
        key = tuple(
            labels.get(k) for k in ("bench_proxy", "bench_size", "bench_log", "bench_connections")
        ) + (labels.get("quantile"),)
        result.setdefault(key, []).append(value)
    return result


def _fmt_range(values, fmt):
    med = statistics.median(values)
    if len(values) == 1:
        return fmt(med)
    return f"{fmt(med)} ({fmt(min(values))}–{fmt(max(values))})"


def summary(run_dir):
    families = {}
    files = _steps(run_dir)
    for raw in files:
        step_families(raw + ".meta.json", raw, families, with_edge=False)
    rps = _values(families, "bench_requests_per_second")
    lat = _values(families, "bench_latency_seconds")
    err = _values(families, "bench_errors_total")
    busy = _values(families, "bench_machine_busy_ratio")
    cores = _values(families, "bench_proxy_cpu_cores")
    per10k = _values(families, "bench_proxy_cpu_cores_per_10k_rps")
    host = families["bench_host"].samples[0][1]

    def latency(key, q):
        values = lat.get(key[:4] + (q,), [])
        return f"{statistics.median(values) * 1000:.2f}" if values else "-"

    out = [f"# Benchmark {host.get('bench_run')}", ""]
    out.append(
        f"Mode `{host.get('bench_mode')}`, commit `{host.get('bench_commit')}`, edge "
        f"`{host.get('bench_edge_version')}`, {host.get('cpu_model')} "
        f"({host.get('cpu_cores', '')} cores), {host.get('os')}, kernel {host.get('kernel')}, "
        f"{host.get('java_version')}, NGINX {host.get('nginx_version')}, HAProxy "
        f"{host.get('haproxy_version')}, shb {host.get('shb_version')}."
    )
    out.append("")
    out.append(
        "req/s: median of the repeats, the range in parentheses. Latencies in ms, medians. "
        "CPU: the middle of the measurement."
    )
    out.append("")
    out.append(
        "| Proxy | Size | Log | Conns | req/s | p50 | p90 | p99 | p99.9 | Errors | Machine busy "
        "| Proxy cores | Cores per 10k req/s |"
    )
    out.append("|" + " --- |" * 13)
    order = {"nginx": 0, "haproxy": 1, "edge": 2}
    size_order = {"0b": 0, "512b": 1, "10kb": 2}

    def sort_key(key):
        return (size_order.get(key[1], 9), int(key[3]), key[2], order.get(key[0], 9))

    for key in sorted(rps, key=sort_key):
        out.append(
            f"| {key[0]} | {key[1]} | {key[2]} | {key[3]} | "
            f"{_fmt_range(rps[key], lambda v: f'{v:,.0f}')} | {latency(key, '0.5')} | "
            f"{latency(key, '0.9')} | {latency(key, '0.99')} | {latency(key, '0.999')} | "
            f"{sum(err.get(key, [0])):.0f} | "
            f"{statistics.median(busy.get(key, [0])) * 100:.0f} % | "
            f"{statistics.median(cores.get(key, [0])):.1f} | "
            f"{statistics.median(per10k.get(key, [0])):.2f} |"
        )
    out.append("")
    out.append("Edge relative to the others, req/s medians:")
    out.append("")
    out.append("| Size | Log | Conns | edge / nginx | edge / haproxy |")
    out.append("|" + " --- |" * 5)
    variants = sorted({k[1:] for k in rps}, key=lambda v: (size_order.get(v[0], 9), int(v[2]), v[1]))
    for size, log, conns, q in variants:
        edge = rps.get(("edge", size, log, conns, q))
        if not edge:
            continue

        def ratio(other):
            values = rps.get((other, size, log, conns, q))
            if not values:
                return "-"
            return f"{statistics.median(edge) / statistics.median(values):.0%}"

        out.append(f"| {size} | {log} | {conns} | {ratio('nginx')} | {ratio('haproxy')} |")
    with open(os.path.join(run_dir, "summary.md"), "w") as f:
        f.write("\n".join(out) + "\n")
    print(f"# {len(rps)} variants from {len(files)} steps")


def main(argv):
    if len(argv) < 2:
        raise SystemExit(__doc__)
    cmd, args = argv[1], argv[2:]
    if cmd == "sample":
        sample(args[0], args[1], args[2:])
    elif cmd == "describe":
        describe(*args)
    elif cmd == "export":
        export(*args)
    elif cmd == "summary":
        summary(args[0])
    elif cmd == "check":
        check(args)
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
