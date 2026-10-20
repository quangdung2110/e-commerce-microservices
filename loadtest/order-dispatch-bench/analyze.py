"""Independently verify benchmark summaries against raw samples; Python stdlib only.

Usage: python analyze.py path/to/results
Does not connect to any service or alter benchmark data.
"""
import csv
import gzip
import json
import math
import statistics
import sys
from pathlib import Path


def samples(path):
    with gzip.open(path, "rt", encoding="utf-8") as stream:
        return list(csv.DictReader(stream))


def verify_stats(values, expected):
    assert len(values) == expected["n"], (len(values), expected)
    if not values:
        return
    values = sorted(values)
    actual = {"mean": statistics.fmean(values), "max": values[-1]}
    actual.update({f"p{p}": values[math.ceil(p / 100 * len(values)) - 1] for p in (50, 95, 99)})
    for key, value in actual.items():
        assert math.isclose(value, expected[key], rel_tol=1e-8, abs_tol=1e-7), (key, value, expected[key])


def verify_entry_stats(rows, expected):
    assert len(rows) == expected["count"]
    for name, begin, end in (
        ("queue_to_rpc_ms", "committed_ns", "rpc_begin_ns"),
        ("claim_to_rpc_ms", "claimed_ns", "rpc_begin_ns"),
        ("gateway_ms", "rpc_begin_ns", "rpc_end_ns"),
        ("commit_to_ack_ms", "committed_ns", "acked_ns"),
    ):
        verify_stats([(int(r[end]) - int(r[begin])) / 1e6 for r in rows], expected[name])


def main(directory):
    folder = Path(directory)
    summary = json.loads((folder / "summary.json").read_text(encoding="utf-8"))
    total = 0
    print("scenario | ops/s | ACK n | commit-to-ACK p50 / p95 / p99 ms | backlog")
    for run in summary:
        rows = samples(folder / (run["scenario"] + ".csv.gz"))
        assert len(rows) == run["enqueued_total"] == run["completed_after_drain"]
        assert len({r["id"] for r in rows}) == len(rows)
        for r in rows:
            times = [int(r[k]) for k in ("committed_ns", "claimed_ns", "rpc_begin_ns", "rpc_end_ns", "acked_ns")]
            assert times == sorted(times), r
            assert r["result"] == ("STARTED" if r["kind"] == "start" else "SIGNALLED"), r
        verify_entry_stats(rows, run["all_including_drain"])
        assert run["nonterminal_or_retried"] == 0
        threads = run.get("dispatcher_threads", 1)
        assert 1 <= run["max_concurrent_gateway_calls"] <= threads
        events = sorted((int(r[k]), delta) for r in rows
                        for k, delta in (("rpc_begin_ns", 1), ("rpc_end_ns", -1)))
        inflight = peak = 0
        for _, delta in events:
            inflight += delta
            peak = max(peak, inflight)
        assert inflight == 0 and 1 <= peak <= threads
        if threads == 1:
            assert run["gateway_threads"] == ["order-workflow-dispatcher"]
        else:
            assert all(t.startswith("order-workflow-start-") or t == "order-workflow-dispatcher"
                       for t in run["gateway_threads"])
        if "window_begin_ns" in run:
            begin, end = run["window_begin_ns"], run["window_end_ns"]
            window = [r for r in rows if begin <= int(r["acked_ns"]) <= end]
            assert len(window) == run["completed_in_window"]
            assert math.isclose(run["throughput_s"], len(window) / ((end - begin) / 1e9))
            verify_entry_stats(window, run["window"])
            for kind in ("start", "message"):
                verify_entry_stats([r for r in window if r["kind"] == kind], run["window_" + kind + "s"])
            backlog = sum(int(r["committed_ns"]) <= end < int(r["acked_ns"]) for r in rows)
            assert backlog == run["backlog_at_window_end"]
            txs = samples(folder / (run["scenario"] + "-transactions.csv.gz"))
            for t in txs:
                assert t["kind"] in ("claim_start", "claim_message", "ack_start", "ack_message", "renew_start"), t
                assert int(t["duration_ns"]) >= 0, t
            for kind in ("start", "message"):
                ack_txs = [t for t in txs if t["kind"] == "ack_" + kind]
                assert sum(int(t.get("rows", 1)) for t in ack_txs) == sum(r["kind"] == kind for r in rows)
                if rows and "ack_batch" in rows[0]:
                    assert len({r["ack_batch"] for r in rows if r["kind"] == kind}) == len(ack_txs)
                    groups = {}
                    for r in rows:
                        if r["kind"] == kind:
                            assert int(r["ack_batch"]) > 0
                            groups.setdefault(r["ack_batch"], set()).add(r["acked_ns"])
                    assert all(len(timestamps) == 1 for timestamps in groups.values())
            if "ack_start_transactions_total" in run:
                assert run["ack_start_transactions_total"] == sum(t["kind"] == "ack_start" for t in txs)
                assert run["ack_start_rows_total"] == sum(r["kind"] == "start" for r in rows)
            for kind, expected in run["transaction_ms"].items():
                verify_stats([int(t["duration_ns"]) / 1e6 for t in txs
                              if t["kind"] == kind and begin <= int(t["end_ns"]) <= end], expected)
        else:
            # Pilot builds before window-bound export still allow whole-run latency verification.
            print("  NOTE: pilot export has no raw window bounds; window count cannot be independently verified")
        if "intended_arrival_to_ack_ms" in run:
            verify_stats([(int(r["acked_ns"]) - int(r["scheduled_ns"])) / 1e6 for r in rows],
                         run["intended_arrival_to_ack_ms"])
            verify_stats([(int(r["committed_ns"]) - int(r["scheduled_ns"])) / 1e6 for r in rows],
                         run["enqueue_lateness_ms"])
        latency = run["all_including_drain"]["commit_to_ack_ms"]
        print(f'{run["scenario"]} | {run["throughput_s"]:.2f} | {len(rows)} | '
              f'{latency["p50"]:.2f} / {latency["p95"]:.2f} / {latency["p99"]:.2f} | '
              f'{run["backlog_at_window_end"]}')
        total += len(rows)
    rates = [r["throughput_s"] for r in summary if r["scenario"].startswith("saturated-starts-")]
    print(f"VERIFIED: {total} operations, {len(summary)} scenarios; saturated median={statistics.median(rates):.2f} start/s")


if __name__ == "__main__":
    main(sys.argv[1])
