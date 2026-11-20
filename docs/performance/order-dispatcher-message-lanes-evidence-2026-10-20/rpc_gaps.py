"""Per-window RPC timeline: gaps with no RPC in flight, slowest RPCs, ACKs per second. Stdlib only.

Usage: python rpc_gaps.py <scenario>.csv.gz
"""
import csv, gzip, sys
path, = sys.argv[1:]
rows = list(csv.DictReader(gzip.open(path, 'rt')))
ev = []
for r in rows:
    ev.append((int(r['rpc_begin_ns']), 1)); ev.append((int(r['rpc_end_ns']), -1))
ev.sort()
# idle periods: no RPC in flight
infl = 0; last = None; idles = []
for t, d in ev:
    if infl == 0 and last is not None and t - last > 300e6:
        idles.append((last/1e9, (t-last)/1e9))
    infl += d
    if infl == 0: last = t
print('rows', len(rows), 'idle>300ms (start_s, len_s):', [(round(a,2), round(b,2)) for a,b in idles][:20])
# slow RPCs
slow = sorted(((int(r['rpc_end_ns'])-int(r['rpc_begin_ns']))/1e6, r['kind']) for r in rows)[-5:]
print('slowest rpc ms', [(round(a), k) for a,k in slow])
# per second acks
from collections import Counter
c = Counter(int(int(r['acked_ns'])/1e9) for r in rows)
print('acks/s', [c[k] for k in sorted(c)])
