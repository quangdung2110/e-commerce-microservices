"""Compare two independently verified benchmark exports; Python standard library only.

Usage: python compare.py old-results new-results
       python compare.py paired-results
Works for legacy vs parallel (ADR-028) and adr028 vs parallel (ADR-029) pairs; with one argument,
compares the adr028 and parallel windows of one paired-profile export (ABBA within one JVM).
Prints JSON; redirect to comparison.json to retain the calculations behind the report.
"""
import json
import statistics
import sys
from pathlib import Path


def load(folder, filename):
    return json.loads((Path(folder) / filename).read_text(encoding="utf-8"))


def median_of(rows, prefix, key="throughput_s"):
    values = [r[key] for name, r in rows.items() if name.startswith(prefix)]
    return statistics.median(values) if values else None


def main(old_dir, new_dir):
    old_env, new_env = load(old_dir, "environment.json"), load(new_dir, "environment.json")
    same = ("java", "os", "available_processors", "jvm_max_heap_bytes", "postgres", "fsync",
            "synchronous_commit", "full_page_writes", "shared_buffers", "dispatch_batch_size",
            "lease_s", "dispatch_interval_ms", "hikari_max", "hikari_min_idle", "window_seconds",
            "saturated_backlog_target", "temporal_workers", "temporal_sdk")
    for key in same:
        assert old_env[key] == new_env[key], (key, old_env[key], new_env[key])
    old_variant = old_env.get("variant", "legacy")
    new_variant = new_env.get("variant", "parallel")
    if old_variant == "legacy":
        assert old_env["dispatcher_threads"] == 1
    assert new_env["dispatcher_threads"] == 8
    if "profile" in old_env or "profile" in new_env:
        assert old_env.get("profile") == new_env.get("profile"), (old_env.get("profile"), new_env.get("profile"))
    old = {r["scenario"]: r for r in load(old_dir, "summary.json")}
    new = {r["scenario"]: r for r in load(new_dir, "summary.json")}
    if old_variant == "legacy":
        for row in old.values():
            assert row["ack_start_transactions_total"] == row["ack_start_rows_total"]

    result = {
        "old_variant": old_variant,
        "new_variant": new_variant,
        "identical_environment_fields": list(same),
        "old_measured_operations": sum(r["enqueued_total"] for r in old.values()),
        "new_measured_operations": sum(r["enqueued_total"] for r in new.values()),
    }
    for label, rows in (("old", old), ("new", new)):
        starts = median_of(rows, "saturated-starts-")
        mixed = median_of(rows, "saturated-mixed-")
        result[f"{label}_saturated_median_start_s"] = starts
        result[f"{label}_saturated_start_windows"] = sorted(
            r["throughput_s"] for n, r in rows.items() if n.startswith("saturated-starts-"))
        if mixed is not None:
            mixed_rows = [r for n, r in rows.items() if n.startswith("saturated-mixed-")]
            result[f"{label}_mixed_median_ops_s"] = mixed
            result[f"{label}_mixed_windows_ops_s"] = sorted(r["throughput_s"] for r in mixed_rows)
            result[f"{label}_mixed_median_starts_s"] = statistics.median(r["starts_per_s"] for r in mixed_rows)
            result[f"{label}_mixed_median_messages_s"] = statistics.median(r["messages_per_s"] for r in mixed_rows)
            result[f"{label}_mixed_to_start_only_ratio"] = mixed / starts
            ops = sum(r["enqueued_total"] for r in mixed_rows)
            if all("ack_transactions_total" in r for r in mixed_rows):
                result[f"{label}_mixed_ack_transactions_per_operation"] = (
                    sum(r["ack_transactions_total"] for r in mixed_rows) / ops)
                result[f"{label}_mixed_dispatcher_transactions_per_operation"] = (
                    sum(r["dispatcher_transactions_total"] for r in mixed_rows) / ops)
            result[f"{label}_mixed_max_concurrent_gateway_calls"] = max(
                r["max_concurrent_gateway_calls"] for r in mixed_rows)
            if all("max_concurrent_messages_per_target" in r for r in mixed_rows):
                result[f"{label}_mixed_max_concurrent_messages_per_target"] = max(
                    r["max_concurrent_messages_per_target"] for r in mixed_rows)
    result["saturated_start_speedup"] = result["new_saturated_median_start_s"] / result["old_saturated_median_start_s"]
    if "old_mixed_median_ops_s" in result and "new_mixed_median_ops_s" in result:
        result["mixed_speedup"] = result["new_mixed_median_ops_s"] / result["old_mixed_median_ops_s"]
    if old_variant == "legacy":
        sat = [n for n in old if n.startswith("saturated-starts-")]
        old_ack = sum(old[n]["ack_start_transactions_total"] for n in sat)
        old_rows = sum(old[n]["ack_start_rows_total"] for n in sat)
        new_ack = sum(new[n]["ack_start_transactions_total"] for n in sat)
        new_rows = sum(new[n]["ack_start_rows_total"] for n in sat)
        result["legacy_ack_transactions_per_start"] = old_ack / old_rows
        result["parallel_ack_transactions_per_start"] = new_ack / new_rows
        result["ack_transactions_per_start_reduction_pct"] = (1 - (new_ack / new_rows) / (old_ack / old_rows)) * 100

    result["common_scenarios"] = []
    for name in sorted(old.keys() & new.keys()):
        a, b = old[name], new[name]
        ap = a["all_including_drain"]["commit_to_ack_ms"]["p95"]
        bp = b["all_including_drain"]["commit_to_ack_ms"]["p95"]
        entry = {
            "scenario": name,
            "old_ack_s": a["throughput_s"], "new_ack_s": b["throughput_s"],
            "old_messages_s": a["messages_per_s"], "new_messages_s": b["messages_per_s"],
            "old_p95_ms": ap, "new_p95_ms": bp, "p95_reduction_pct": (1 - bp / ap) * 100,
            "old_backlog": a["backlog_at_window_end"], "new_backlog": b["backlog_at_window_end"],
        }
        for kind in ("starts", "messages"):
            oa = a["window_" + kind]["commit_to_ack_ms"]
            nb = b["window_" + kind]["commit_to_ack_ms"]
            if oa.get("n") and nb.get("n"):
                entry[f"old_window_{kind}_p95_ms"] = oa["p95"]
                entry[f"new_window_{kind}_p95_ms"] = nb["p95"]
        if "committed_per_s" in a:
            entry["old_committed_per_s"] = a["committed_per_s"]
            entry["new_committed_per_s"] = b["committed_per_s"]
        result["common_scenarios"].append(entry)
    print(json.dumps(result, indent=2))


def paired(folder):
    """One export of the paired profile: adr028 and parallel windows alternate (ABBA) in one JVM."""
    env = load(folder, "environment.json")
    assert env.get("profile") == "paired", env.get("profile")
    rows = load(folder, "summary.json")
    by = {}
    for r in rows:
        v = r["variant"]
        assert r["scenario"].endswith("-" + v) or f"-{v}-" in r["scenario"], r["scenario"]
        by.setdefault(v, []).append(r)
    result = {"profile": "paired", "variants": sorted(by), "measured_operations": sum(r["enqueued_total"] for r in rows)}
    for v, vr in by.items():
        mixed = [r for r in vr if r["scenario"].startswith("saturated-mixed-")]
        starts = [r for r in vr if r["scenario"].startswith("saturated-starts-")]
        ops = sum(r["enqueued_total"] for r in mixed)
        result[v] = {
            "mixed_windows_ops_s": [round(r["throughput_s"], 2) for r in mixed],
            "mixed_median_ops_s": statistics.median(r["throughput_s"] for r in mixed),
            "mixed_median_starts_s": statistics.median(r["starts_per_s"] for r in mixed),
            "mixed_median_messages_s": statistics.median(r["messages_per_s"] for r in mixed),
            "start_windows_s": [round(r["throughput_s"], 2) for r in starts],
            "start_median_s": statistics.median(r["throughput_s"] for r in starts),
            "mixed_ack_transactions_per_operation": sum(r["ack_transactions_total"] for r in mixed) / ops,
            "mixed_dispatcher_transactions_per_operation": sum(r["dispatcher_transactions_total"] for r in mixed) / ops,
            "mixed_max_concurrent_gateway_calls": max(r["max_concurrent_gateway_calls"] for r in mixed),
            "max_concurrent_messages_per_target": max(r["max_concurrent_messages_per_target"] for r in vr),
        }
        result[v]["mixed_to_start_only_ratio"] = result[v]["mixed_median_ops_s"] / result[v]["start_median_s"]
    name = {r["scenario"]: r for r in rows}
    rounds = []
    for i in range(1, 100):
        old, new = name.get(f"saturated-mixed-50-50-adr028-{i}"), name.get(f"saturated-mixed-50-50-parallel-{i}")
        if not old or not new:
            break
        so, sn = name[f"saturated-starts-adr028-{i}"], name[f"saturated-starts-parallel-{i}"]
        rounds.append({"round": i, "mixed_old": old["throughput_s"], "mixed_new": new["throughput_s"],
                       "mixed_ratio": new["throughput_s"] / old["throughput_s"],
                       "starts_old": so["throughput_s"], "starts_new": sn["throughput_s"],
                       "starts_ratio": sn["throughput_s"] / so["throughput_s"]})
    result["rounds"] = rounds
    result["median_round_mixed_ratio"] = statistics.median(r["mixed_ratio"] for r in rounds)
    result["median_round_starts_ratio"] = statistics.median(r["starts_ratio"] for r in rounds)
    result["mixed_median_speedup"] = result["parallel"]["mixed_median_ops_s"] / result["adr028"]["mixed_median_ops_s"]
    arrivals = []
    for r in rows:
        if r["scenario"].startswith("arrival-mixed-"):
            arrivals.append({"scenario": r["scenario"], "variant": r["variant"], "rate": r["arrival_rate_s"],
                             "committed_per_s": r["committed_per_s"], "ack_s": r["throughput_s"],
                             "messages_s": r["messages_per_s"], "backlog": r["backlog_at_window_end"],
                             "p95_ms": r["all_including_drain"]["commit_to_ack_ms"]["p95"],
                             "p99_ms": r["all_including_drain"]["commit_to_ack_ms"]["p99"],
                             "message_p95_ms": r["window_messages"]["commit_to_ack_ms"].get("p95"),
                             "producer_elapsed_s": r.get("producer_elapsed_s")})
    result["arrivals"] = arrivals
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    if len(sys.argv) == 2:
        paired(sys.argv[1])
    else:
        main(*sys.argv[1:])
