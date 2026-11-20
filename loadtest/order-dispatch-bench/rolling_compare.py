"""Derive rolling-scheduler comparison and idle-slot time from raw exports. Python stdlib only.

Run analyze.py first, then: python rolling_compare.py RESULTS_DIR
Writes rolling-comparison.json; never connects to a service.
"""
import csv
import gzip
import itertools
import json
import statistics
import sys
from pathlib import Path


def idle_with_queue(folder, run):
    with gzip.open(folder / (run["scenario"] + ".csv.gz"), "rt", encoding="utf-8") as stream:
        rows = list(csv.DictReader(stream))
    events = []
    for row in rows:
        events.extend(((int(row["committed_ns"]), 1, 0), (int(row["rpc_begin_ns"]), -1, 1),
                       (int(row["rpc_end_ns"]), 0, -1)))
    queued = active = 0
    previous = 0
    idle_ns = lost_slot_ns = 0
    begin, end = run["window_begin_ns"], run["window_end_ns"]
    for stamp, group in itertools.groupby(sorted(events), key=lambda event: event[0]):
        duration = max(0, min(stamp, end) - max(previous, begin))
        if queued > 0 and active < 8:
            idle_ns += duration
            lost_slot_ns += duration * (8 - active)
        for _, queue_delta, active_delta in group:
            queued += queue_delta
            active += active_delta
        assert queued >= 0 and 0 <= active <= 8
        previous = stamp
    assert queued == active == 0
    return {"seconds_with_queue_and_free_slots": idle_ns / 1e9,
            "free_slot_seconds_with_queue": lost_slot_ns / 1e9}


def main(directory):
    folder = Path(directory)
    runs = json.loads((folder / "summary.json").read_text(encoding="utf-8"))
    output = {"saturated": {}, "arrivals": [], "scenarios": []}
    for run in runs:
        derived = {"scenario": run["scenario"], "variant": run["variant"], **idle_with_queue(folder, run)}
        output["scenarios"].append(derived)
        if run["arrival_rate_s"]:
            latency = run["all_including_drain"]["commit_to_ack_ms"]
            output["arrivals"].append({"scenario": run["scenario"], "variant": run["variant"],
                "offered_ops_s": run["arrival_rate_s"], "ack_ops_s": run["throughput_s"],
                "committed_ops_s": run["committed_per_s"], "backlog": run["backlog_at_window_end"],
                "p95_ms": latency["p95"], "p99_ms": latency["p99"],
                "uninjected_p99_ms": run["uninjected_including_drain"]["commit_to_ack_ms"]["p99"],
                "producer_elapsed_s": run["producer_elapsed_s"]})
    for mode in ("healthy", "tail"):
        matched = [r for r in runs if r["scenario"].startswith("saturated-mixed-" + mode + "-")]
        per_variant = {}
        for variant in ("adr029", "parallel"):
            data = [r for r in matched if r["variant"] == variant]
            if not data:
                continue
            per_variant[variant] = {
                "windows": len(data), "median_ack_ops_s": statistics.median(r["throughput_s"] for r in data),
                "range_ack_ops_s": [min(r["throughput_s"] for r in data), max(r["throughput_s"] for r in data)],
                "median_slot_utilization": statistics.median(r["rpc_slot_utilization"] for r in data),
                "median_ack_transactions_per_operation": statistics.median(r["ack_transactions_per_operation"] for r in data),
                "median_dispatcher_transactions_per_operation": statistics.median(r["dispatcher_transactions_per_operation"] for r in data),
                "median_free_slot_seconds_with_queue": statistics.median(d["free_slot_seconds_with_queue"]
                    for d in output["scenarios"] if d["variant"] == variant and d["scenario"].startswith("saturated-mixed-" + mode + "-")),
            }
        ratios = []
        for round_id in (1, 2, 3, 4):
            pair = {r["variant"]: r for r in matched if r["scenario"].endswith("-" + str(round_id))}
            if "adr029" in pair and "parallel" in pair:
                ratios.append(pair["parallel"]["throughput_s"] / pair["adr029"]["throughput_s"])
        output["saturated"][mode] = {"variants": per_variant, "paired_throughput_ratios": ratios,
            "median_paired_ratio": statistics.median(ratios) if ratios else None}
    (folder / "rolling-comparison.json").write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"saturated": output["saturated"], "arrivals": output["arrivals"]}, indent=2))


if __name__ == "__main__":
    main(sys.argv[1])
