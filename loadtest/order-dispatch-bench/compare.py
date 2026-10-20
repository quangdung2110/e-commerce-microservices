"""Compare independently verified legacy/parallel exports; Python standard library only.

Usage: python compare.py legacy-results parallel-results
Prints JSON; redirect to comparison.json to retain the calculations behind the report.
"""
import json
import statistics
import sys
from pathlib import Path


def load(folder, filename):
    return json.loads((Path(folder) / filename).read_text(encoding="utf-8"))


def main(legacy, parallel):
    old_env, new_env = load(legacy, "environment.json"), load(parallel, "environment.json")
    same = ("java", "os", "available_processors", "jvm_max_heap_bytes", "postgres", "fsync",
            "synchronous_commit", "full_page_writes", "shared_buffers", "dispatch_batch_size",
            "lease_s", "dispatch_interval_ms", "hikari_max", "hikari_min_idle", "window_seconds",
            "saturated_backlog_target", "temporal_workers", "temporal_sdk")
    for key in same:
        assert old_env[key] == new_env[key], (key, old_env[key], new_env[key])
    assert old_env["dispatcher_threads"] == 1 and new_env["dispatcher_threads"] == 8
    old = {r["scenario"]: r for r in load(legacy, "summary.json")}
    new = {r["scenario"]: r for r in load(parallel, "summary.json")}
    for row in old.values():
        assert row["ack_start_transactions_total"] == row["ack_start_rows_total"]
    sat_names = [f"saturated-starts-{i}" for i in (1, 2, 3)]
    old_median = statistics.median(old[n]["throughput_s"] for n in sat_names)
    new_median = statistics.median(new[n]["throughput_s"] for n in sat_names)
    old_ack = sum(old[n]["ack_start_transactions_total"] for n in sat_names)
    old_rows = sum(old[n]["ack_start_rows_total"] for n in sat_names)
    new_ack = sum(new[n]["ack_start_transactions_total"] for n in sat_names)
    new_rows = sum(new[n]["ack_start_rows_total"] for n in sat_names)
    result = {
        "identical_environment_fields": list(same),
        "legacy_saturated_median_start_s": old_median,
        "parallel_saturated_median_start_s": new_median,
        "saturated_speedup": new_median / old_median,
        "legacy_ack_transactions_per_start": old_ack / old_rows,
        "parallel_ack_transactions_per_start": new_ack / new_rows,
        "parallel_mean_rows_per_ack": new_rows / new_ack,
        "ack_transactions_per_start_reduction_pct": (1 - (new_ack / new_rows) / (old_ack / old_rows)) * 100,
        "legacy_measured_operations": sum(r["enqueued_total"] for r in old.values()),
        "parallel_measured_operations": sum(r["enqueued_total"] for r in new.values()),
        "common_scenarios": [],
    }
    for name in old.keys() & new.keys():
        a, b = old[name], new[name]
        ap = a["all_including_drain"]["commit_to_ack_ms"]["p95"]
        bp = b["all_including_drain"]["commit_to_ack_ms"]["p95"]
        result["common_scenarios"].append({
            "scenario": name,
            "legacy_ack_s": a["throughput_s"], "parallel_ack_s": b["throughput_s"],
            "legacy_p95_ms": ap, "parallel_p95_ms": bp, "p95_reduction_pct": (1 - bp / ap) * 100,
            "legacy_backlog": a["backlog_at_window_end"], "parallel_backlog": b["backlog_at_window_end"],
        })
    result["common_scenarios"].sort(key=lambda r: r["scenario"])
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main(*sys.argv[1:])
