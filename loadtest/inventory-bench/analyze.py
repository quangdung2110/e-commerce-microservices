"""Summarises a scripts/ci/inventory-bench.sh run into tables for the benchmark report.

    python loadtest/inventory-bench/analyze.py loadtest/inventory-bench/results/<run-id>

Reads every <variant>-<scenario>/ directory of the run and writes <run>/summary.json and <run>/summary.md: per step
the k6 result (RESERVED per second, outcome counts per SKU class, latency percentiles, dropped arrivals), whether it
meets the Reserve SLO (docs/inventory/operations.md: p95 <= 200 ms, p99 <= 500 ms; plus <= 1% non-RESERVED, the
INVENTORY_BUSY alert threshold, and <= 1% dropped arrivals), mean/max CPU per container, Hikari pending/active and
connection timeouts, PostgreSQL backends waiting on a lock, the mean time of the stock_position lock statement,
commits/rollbacks/deadlocks, and the correctness check (hot reserved delta == hot RESERVED responses).
Standard library only.
"""
import json
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path

SLO_P95_MS = 200
SLO_P99_MS = 500
SLO_ERROR_RATE = 0.01
SLO_DROPPED_RATE = 0.01
SERVICES = ["inventory-service", "postgres"]
SAMPLE = re.compile(r"^(\d+) ([a-z_]+)(\{[^}]*\})? ([0-9.eE+-]+)$")
LOCK_SQL = "FROM stock_position WHERE warehouse_id = $1 AND s"


def cpu_stats(path):
    per = defaultdict(list)
    if not path.exists():
        return {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        parts = line.split(",")
        if len(parts) < 3 or not parts[2].endswith("%"):
            continue
        for service in SERVICES:
            if parts[1] == f"inventory-bench-{service}-1":
                per[service].append(float(parts[2].rstrip("%")))
    # The first and last samples straddle the step boundaries.
    return {s: {"mean": round(statistics.mean(v[1:-1] or v), 1), "max": round(max(v), 1)} for s, v in per.items()}


def prometheus(path):
    series = defaultdict(list)  # name+labels -> [(ts, value)]
    if not path.exists():
        return series
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        match = SAMPLE.match(line.strip())
        if match:
            ts, name, labels, value = match.groups()
            series[name + (labels or "")].append((int(ts), float(value)))
    return series


def gauge(series, name):
    values = [v for key, samples in series.items() if key.startswith(name) for _, v in samples]
    return {"mean": round(statistics.mean(values), 1), "max": round(max(values), 1)} if values else None


def counter_delta(series, name, **labels):
    total = 0.0
    for key, samples in series.items():
        if key.startswith(name) and all(f'{k}="{v}"' in key for k, v in labels.items()):
            total += samples[-1][1] - samples[0][1]
    return total


def pg_activity(path):
    rows = []
    if path.exists():
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            parts = line.split()
            if len(parts) == 5 and all(p.isdigit() for p in parts):
                rows.append([int(p) for p in parts])
    # Only samples taken while load was running: at least one active backend.
    busy = [r for r in rows if r[2] > 0] or rows
    if not busy:
        return None
    return {
        "lock_waiters_mean": round(statistics.mean(r[1] for r in busy), 1),
        "lock_waiters_max": max(r[1] for r in busy),
        "active_mean": round(statistics.mean(r[2] for r in busy), 1),
        "backends_max": max(r[4] for r in busy),
    }


def db_file(path):
    values = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            key, _, rest = line.partition("=")
            values[key] = [int(x) for x in rest.split(" | ") if x.strip().lstrip("-").isdigit()]
    return values


def sql_stats(path):
    statements = []
    if path.exists():
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            parts = line.split(" | ", 3)
            if len(parts) == 4:
                statements.append({"calls": int(parts[0]), "total_ms": float(parts[1]), "mean_ms": float(parts[2]),
                                   "query": parts[3]})
    lock = next((s for s in statements if LOCK_SQL in s["query"] and s["query"].startswith("SELECT")), None)
    return {
        "position_lock_mean_ms": lock["mean_ms"] if lock else None,
        "position_lock_share_of_sql_time": round(lock["total_ms"] / sum(s["total_ms"] for s in statements), 3)
        if lock and statements else None,
        "top": statements[:8],
    }


def step_summary(directory, rate):
    k6 = json.loads((directory / f"rate-{rate}-k6.json").read_text(encoding="utf-8"))
    seconds = int(re.match(r"\d+", k6["duration"]).group()) * (60 if k6["duration"].endswith("m") else 1)
    outcomes = k6["outcomes"]
    total = sum(sum(c.values()) for c in outcomes.values())
    reserved = sum(c["reserved"] for c in outcomes.values())
    failed = total - reserved
    arrivals = total + k6["dropped_iterations"]
    lat = {}
    for cls in ("hot", "cold"):
        values = k6["latency_ms"][cls]["all"]
        if values:
            lat[cls] = {k: round(values[key], 1) for k, key in
                        (("p50", "med"), ("p95", "p(95)"), ("p99", "p(99)"), ("max", "max"))}
    worst_p95 = max((v["p95"] for v in lat.values()), default=0)
    worst_p99 = max((v["p99"] for v in lat.values()), default=0)
    error_rate = failed / total if total else 1.0
    dropped_rate = k6["dropped_iterations"] / arrivals if arrivals else 0.0
    series = prometheus(directory / f"rate-{rate}-prometheus.txt")
    db = db_file(directory / f"rate-{rate}-db.txt")
    hot_delta = db["hot_after"][0] - db["hot_before"][0] if db.get("hot_after") and db.get("hot_before") else None
    commits = db["db_after"][0] - db["db_before"][0] if db.get("db_after") and db.get("db_before") else None
    rollbacks = db["db_after"][1] - db["db_before"][1] if db.get("db_after") and db.get("db_before") else None
    deadlocks = db["db_after"][2] - db["db_before"][2] if db.get("db_after") and db.get("db_before") else None
    return {
        "offered_per_s": rate,
        "hot_share": k6["hot_share"],
        "reserved_per_s": round(reserved / seconds, 1),
        "requests": total,
        "dropped": k6["dropped_iterations"],
        "dropped_rate": round(dropped_rate, 4),
        "outcomes": outcomes,
        "error_rate": round(error_rate, 4),
        "latency_ms": lat,
        "meets_slo": worst_p95 <= SLO_P95_MS and worst_p99 <= SLO_P99_MS and error_rate <= SLO_ERROR_RATE
        and dropped_rate <= SLO_DROPPED_RATE,
        "cpu": cpu_stats(directory / f"rate-{rate}-docker-stats.csv"),
        "hikari_pending": gauge(series, "hikaricp_connections_pending"),
        "hikari_active": gauge(series, "hikaricp_connections_active"),
        "hikari_timeouts": counter_delta(series, "hikaricp_connections_timeout_total"),
        "busy_counter": counter_delta(series, "inventory_commands_total", operation="RESERVE_ORDER", result="busy"),
        "pg": pg_activity(directory / f"rate-{rate}-pg-activity.txt"),
        "sql": sql_stats(directory / f"rate-{rate}-sql.txt"),
        "commits": commits,
        "rollbacks": rollbacks,
        "deadlocks": deadlocks,
        "hot_reserved_delta": hot_delta,
        "hot_reserved_responses": outcomes["hot"]["reserved"],
        "correct": hot_delta == outcomes["hot"]["reserved"] if hot_delta is not None else None,
    }


def fmt(value, digits=0):
    if value is None:
        return "—"
    return f"{value:,.{digits}f}".replace(",", " ")


def main(run_dir):
    run = Path(run_dir)
    result = {}
    lines = [f"# Inventory Reserve benchmark — {run.name}", ""]
    for directory in sorted(p for p in run.iterdir() if p.is_dir()):
        rates = sorted(int(m.group(1)) for f in directory.glob("rate-*-k6.json")
                       if (m := re.match(r"rate-(\d+)-k6\.json", f.name)))
        steps = [step_summary(directory, r) for r in rates]
        result[directory.name] = steps
        lines += [f"## {directory.name}", "",
                  "| Offered/s | RESERVED/s | SLO | Hot p50 / p95 / p99 ms | Cold p50 / p95 / p99 ms | Busy | Unavail. | "
                  "Timeout | Dropped | Lock stmt mean ms | Lock waiters mean/max | Hikari pending mean/max | "
                  "CPU inv / pg % | Correct |",
                  "|" + "---:|" * 14]
        for s in steps:
            def lat(cls):
                v = s["latency_ms"].get(cls)
                return f"{fmt(v['p50'], 1)} / {fmt(v['p95'], 1)} / {fmt(v['p99'], 1)}" if v else "—"
            count = lambda r: sum(c[r] for c in s["outcomes"].values())
            pg = s["pg"] or {}
            hp = s["hikari_pending"] or {}
            cpu = s["cpu"]
            lines.append(
                f"| {s['offered_per_s']} | {fmt(s['reserved_per_s'], 1)} | {'yes' if s['meets_slo'] else 'no'} | "
                f"{lat('hot')} | {lat('cold')} | {count('busy')} | {count('unavailable')} | {count('timeout')} | "
                f"{s['dropped']} | {fmt(s['sql']['position_lock_mean_ms'], 1)} | "
                f"{fmt(pg.get('lock_waiters_mean'), 1)} / {fmt(pg.get('lock_waiters_max'))} | "
                f"{fmt(hp.get('mean'), 1)} / {fmt(hp.get('max'))} | "
                f"{fmt(cpu.get('inventory-service', {}).get('mean'))} / {fmt(cpu.get('postgres', {}).get('mean'))} | "
                f"{'yes' if s['correct'] else ('—' if s['correct'] is None else 'NO')} |")
        lines.append("")
    (run / "summary.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    (run / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main(sys.argv[1])
