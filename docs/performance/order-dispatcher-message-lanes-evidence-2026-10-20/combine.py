import json, statistics, sys
E = sys.argv[1]
c = [json.load(open(f"{E}/paired-{i}/comparison.json")) for i in (1, 2)]
for i, d in enumerate(c, 1):
    print(f"paired-{i}")
    for v in ("adr028", "parallel"):
        x = d[v]
        print(f"  {v}: mixed {x['mixed_windows_ops_s']} med {x['mixed_median_ops_s']:.2f} st {x['mixed_median_starts_s']:.2f} msg {x['mixed_median_messages_s']:.2f} | starts {x['start_windows_s']} med {x['start_median_s']:.2f} | ackTx/op {x['mixed_ack_transactions_per_operation']:.3f} txs/op {x['mixed_dispatcher_transactions_per_operation']:.3f} ratio {x['mixed_to_start_only_ratio']:.3f}")
    print("  rounds", [(r['round'], round(r['mixed_ratio'], 2), round(r['starts_ratio'], 3)) for r in d['rounds']])
    print("  median round mixed", round(d['median_round_mixed_ratio'], 3), "starts", round(d['median_round_starts_ratio'], 3), "median speedup", round(d['mixed_median_speedup'], 3))
    for a in d['arrivals']:
        print("  ", a['scenario'], round(a['committed_per_s'], 1), round(a['ack_s'], 1), a['backlog'], round(a['p95_ms'], 1), round(a['p99_ms'], 1), round(a['message_p95_ms'], 1))
allm = {v: [w for d in c for w in d[v]['mixed_windows_ops_s']] for v in ("adr028", "parallel")}
alls = {v: [w for d in c for w in d[v]['start_windows_s']] for v in ("adr028", "parallel")}
rounds = [r for d in c for r in d['rounds']]
print("ALL mixed medians", {v: statistics.median(x) for v, x in allm.items()}, "starts", {v: statistics.median(x) for v, x in alls.items()})
print("ALL round mixed ratios", sorted(round(r['mixed_ratio'], 2) for r in rounds), "median", statistics.median(r['mixed_ratio'] for r in rounds))
print("ALL round start ratios", sorted(round(r['starts_ratio'], 3) for r in rounds), "median", statistics.median(r['starts_ratio'] for r in rounds))
msg = {v: statistics.median([w for d in c for w in [d[v]['mixed_median_messages_s']]]) for v in ("adr028", "parallel")}
print("ack/op", [(d['adr028']['mixed_ack_transactions_per_operation'], d['parallel']['mixed_ack_transactions_per_operation']) for d in c])
print("ops", [d['measured_operations'] for d in c])
