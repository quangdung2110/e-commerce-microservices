"""Summarises a scripts/ci/pdp-bench.sh run into tables for the benchmark report.

    python loadtest/pdp-bench/analyze.py loadtest/pdp-bench/results/<run-id>

Reads every <variant>-<dist>/ directory of the run and writes <run>/summary.json and <run>/summary.md:
per step the k6 result (goodput, latency percentiles, statuses, dropped iterations), whether it meets the PDP SLO
(docs/product-detail-bff/operations.md: p95 <= 300 ms, p99 <= 800 ms; plus < 0.1% non-200 and <= 1% dropped arrivals),
SQL statements per second and per PDP request by database (pg_stat_statements), mean/max CPU per container
(docker stats), Product Hikari pending/active (sampled every 2 s) and, from BFF counters, Product calls and cache
results. Standard library only.
"""
import json
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path

SLO_P95_MS = 300
SLO_P99_MS = 800
SLO_ERROR_RATE = 0.001
# Arrivals k6 could not start (dropped_iterations) are partly the generator allocating VUs mid-step, so they only
# fail a step above 1%; the column is reported on its own.
SLO_DROPPED_RATE = 0.01
SERVICES = ["product-detail-bff", "product-service", "inventory-service", "review-service", "postgres"]
SAMPLE = re.compile(r"^(\d+) (\w+) ([a-z_]+)(\{[^}]*\})? ([0-9.eE+-]+)$")


def seconds(duration):
    match = re.fullmatch(r"(\d+)(s|m)", duration)
    return int(match.group(1)) * (60 if match.group(2) == "m" else 1)


def cpu_stats(path):
    per = defaultdict(list)
    if not path.exists():
        return {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        parts = line.split(",")
        if len(parts) < 3 or not parts[2].endswith("%"):
            continue
        name = parts[1]
        for service in SERVICES:
            if name == f"pdp-bench-{service}-1":
                per[service].append(float(parts[2].rstrip("%")))
    # The first and last samples straddle the step boundaries.
    return {s: {"mean": round(statistics.mean(v[1:-1] or v), 1), "max": round(max(v), 1)} for s, v in per.items()}


def prometheus(path):
    hikari = defaultdict(list)
    counters = defaultdict(list)  # key -> [(ts, value)]
    if not path.exists():
        return hikari, counters
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        match = SAMPLE.match(line.strip())
        if not match:
            continue
        ts, source, name, labels, value = match.groups()
        value = float(value)
        if source == "product" and name.startswith("hikaricp_connections_"):
            hikari[name.removeprefix("hikaricp_connections_")].append(value)
        elif source == "bff":
            counters[name + (labels or "")].append((int(ts), value))
    return hikari, counters


def delta(counters, prefix, **labels):
    total = 0.0
    for key, samples in counters.items():
        if not key.startswith(prefix + "{") and key != prefix:
            continue
        if all(f'{k}="{v}"' in key for k, v in labels.items()):
            total += samples[-1][1] - samples[0][1]
    return total


def sql_calls(path):
    calls = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            if "|" in line:
                db, count = line.split("|")
                calls[db] = int(count)
    return calls


def step_summary(directory, rate):
    k6 = json.loads((directory / f"rate-{rate}-k6.json").read_text(encoding="utf-8"))
    duration = seconds(k6["duration"])
    statuses = {k: v for k, v in k6["statuses"].items() if v}
    ok = statuses.get("200", 0)
    total = k6["requests"]
    errors = total - ok
    error_rate = errors / total if total else 0
    latency = k6["latency_ms"]
    dropped = k6["dropped_iterations"]
    hikari, counters = prometheus(directory / f"rate-{rate}-prometheus.txt")
    calls = sql_calls(directory / f"rate-{rate}-sql-calls.txt")
    product_calls = delta(counters, "pdp_dependency_calls_seconds_count", dependency="product")
    product_calls_sent = sum(
        delta(counters, "pdp_dependency_calls_seconds_count", dependency="product", outcome=o)
        for o in ["success", "timeout", "connect_error", "5xx", "4xx", "contract_violation"])
    cache = {r: delta(counters, "pdp_catalog_cache_requests_total", result=r) for r in ["hit", "miss", "coalesced"]}
    lookups = sum(cache.values())
    return {
        "rate": rate,
        "requests": total,
        "goodput_rps": round(ok / duration, 1),
        "achieved_rps": round(k6["achieved_rps"], 1),
        "dropped": dropped,
        "error_rate": round(error_rate, 5),
        "statuses": statuses,
        "p50_ms": round(latency.get("med", 0), 1),
        "p95_ms": round(latency.get("p(95)", 0), 1),
        "p99_ms": round(latency.get("p(99)", 0), 1),
        "max_ms": round(latency.get("max", 0), 1),
        "slo_met": latency.get("p(95)", 1e9) <= SLO_P95_MS and latency.get("p(99)", 1e9) <= SLO_P99_MS
                   and error_rate < SLO_ERROR_RATE and dropped <= (total + dropped) * SLO_DROPPED_RATE,
        "sql_per_s": {db: round(n / duration, 1) for db, n in calls.items()},
        "product_sql_per_request": round(calls.get("product_service", 0) / total, 2) if total else None,
        "cpu_percent": cpu_stats(directory / f"rate-{rate}-docker-stats.csv"),
        "product_hikari": {k: {"mean": round(statistics.mean(v), 1), "max": max(v)} for k, v in hikari.items() if v},
        "bff_product_calls_per_s": round(product_calls_sent / duration, 1),
        "bff_product_outcomes": {o: int(delta(counters, "pdp_dependency_calls_seconds_count",
                                              dependency="product", outcome=o))
                                 for o in ["success", "timeout", "connect_error", "circuit_open",
                                           "bulkhead_rejected", "budget_skipped"]
                                 if delta(counters, "pdp_dependency_calls_seconds_count",
                                          dependency="product", outcome=o)},
        "bff_product_lookups": int(product_calls),
        "cache": {**{k: int(v) for k, v in cache.items()},
                  "hit_ratio": round(cache["hit"] / lookups, 4) if lookups else None},
    }


def variant_summary(directory):
    rates = sorted(int(p.name.split("-")[1]) for p in directory.glob("rate-*-k6.json"))
    steps = [step_summary(directory, rate) for rate in rates]
    passing = [s["rate"] for s in steps if s["slo_met"]]
    # Contiguous capacity: the highest rate below which every step met the SLO.
    capacity = None
    for step in steps:
        if not step["slo_met"]:
            break
        capacity = step["rate"]
    return {"environment": (directory / "environment.txt").read_text(encoding="utf-8"),
            "steps": steps, "slo_rates": passing, "capacity_rps": capacity,
            "highest_slo_rate": max(passing, default=None),
            "max_goodput_rps": max((s["goodput_rps"] for s in steps), default=0)}


def markdown(run):
    lines = []
    for name, data in run.items():
        lines.append(f"## {name}\n")
        lines.append(f"Contiguous SLO capacity: **{data['capacity_rps']} req/s**; highest rate meeting the SLO "
                     f"{data['highest_slo_rate']} req/s; "
                     f"max goodput {data['max_goodput_rps']} req/s\n")
        lines.append("| Rate | Goodput | p50 | p95 | p99 | Non-200 | Dropped | SLO | Product SQL/s | SQL/req | "
                     "Product calls/s | Hit ratio | CPU BFF | CPU Product | CPU PG | Hikari pending max |")
        lines.append("|---:|---:|---:|---:|---:|---:|---:|:-:|---:|---:|---:|---:|---:|---:|---:|---:|")
        for s in data["steps"]:
            cpu = s["cpu_percent"]
            lines.append("| {rate} | {good} | {p50} | {p95} | {p99} | {err:.2%} | {drop} | {slo} | {sql} | {sqlr} | "
                         "{pc} | {hit} | {cb} | {cp} | {cg} | {hk} |".format(
                rate=s["rate"], good=s["goodput_rps"], p50=s["p50_ms"], p95=s["p95_ms"], p99=s["p99_ms"],
                err=s["error_rate"], drop=s["dropped"], slo="yes" if s["slo_met"] else "no",
                sql=s["sql_per_s"].get("product_service", "-"), sqlr=s["product_sql_per_request"],
                pc=s["bff_product_calls_per_s"],
                hit="-" if s["cache"]["hit_ratio"] is None else f"{s['cache']['hit_ratio']:.1%}",
                cb=cpu.get("product-detail-bff", {}).get("mean", "-"),
                cp=cpu.get("product-service", {}).get("mean", "-"),
                cg=cpu.get("postgres", {}).get("mean", "-"),
                hk=s["product_hikari"].get("pending", {}).get("max", "-")))
        lines.append("")
    return "\n".join(lines)


def main():
    root = Path(sys.argv[1])
    run = {d.name: variant_summary(d) for d in sorted(root.iterdir()) if d.is_dir() and (d / "environment.txt").exists()}
    (root / "summary.json").write_text(json.dumps(run, indent=2, ensure_ascii=False), encoding="utf-8")
    (root / "summary.md").write_text(markdown(run), encoding="utf-8")
    print(markdown(run))


if __name__ == "__main__":
    main()
