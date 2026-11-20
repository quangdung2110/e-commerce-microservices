"""Summarize retained k6/Prometheus evidence; standard library, no hidden result filtering.
python analyze_read_model.py /results > summary.json
"""
import gzip
import csv
import json
import re
import sys
from datetime import datetime
from pathlib import Path

def counter(path, query):
    if not path.exists():
        return None
    return sum(float(line.rsplit(' ', 1)[1]) for line in path.read_text().splitlines()
               if line.startswith('es_query_duration_seconds_count{') and f'query_type="{query}"' in line)

def main(root, labels=None):
    output = {}
    for envpath in sorted(Path(root).glob('*/environment.json')):
        label = envpath.parent.name
        if labels and label not in labels:
            continue
        result = {'environment': json.loads(envpath.read_text(encoding='utf-8-sig')), 'sizes': {}}
        for size in sorted(envpath.parent.glob('[0-9]*')):
            runs = {}
            swap_time = None
            swap_iso = None
            paired_swap = None
            log = size / 'search.log'
            if log.exists():
                for line in log.read_text(encoding='utf-8-sig').splitlines():
                    if 'atomic alias cutover:' not in line:
                        continue
                    try:
                        item = json.loads(line[line.index('{'):])
                        swap_iso = item['@timestamp']
                        swap_time = datetime.fromisoformat(swap_iso.replace('Z', '+00:00')).timestamp()
                        paired_swap = 'product_cards' in item['message']
                    except (ValueError, KeyError):
                        pass
            for name in ['warmup', 'home', 'browse', 'cutover-home']:
                path = size / f'{name}.json'
                if not path.exists():
                    continue
                data = json.loads(path.read_text())
                metrics = data['metrics']
                def values(key):
                    return metrics.get(key, {}).get('values', {})
                runs[name] = {
                    'completed_views': values('iterations').get('count', 0),
                    'success_views': values('successful_views').get('count', 0),
                    'success_http_reads': values('successful_reads').get('count', 0),
                    'view_failure_rate': values('read_failure').get('rate'),
                    'dropped_views': values('dropped_iterations').get('count', 0),
                    'total_mismatch': values('total_mismatch').get('count', 0),
                    'wall_s': data['state']['testRunDurationMs'] / 1000,
                    'latency_ms_all_outcomes': values('read_duration'),
                    'latency_ms_success': values('successful_view_duration'),
                }
                raw = size / f'{name}.csv.gz'
                phases = {}
                statuses = {}
                after_swap = {}
                if raw.exists():
                    with gzip.open(raw, 'rt') as stream:
                        for row in csv.DictReader(stream):
                            if row['metric_name'] == 'http_reqs':
                                code = row.get('status', 'unknown')
                                statuses[code] = statuses.get(code, 0) + int(float(row['metric_value']))
                            if name == 'cutover-home' and swap_time is not None and row['metric_name'] == 'http_req_duration':
                                # CSV completion timestamps have 1s precision. Subtract duration
                                # from the FLOOR timestamp: only count sends certainly after swap.
                                send_lower_bound = float(row['timestamp']) - float(row['metric_value']) / 1000
                                if send_lower_bound > swap_time:
                                    code = row.get('status', 'unknown')
                                    after_swap[code] = after_swap.get(code, 0) + 1
                            if row['metric_name'] != 'phase_failure':
                                continue
                            match = re.search(r'phase=([0-9-]+)', row.get('extra_tags', ''))
                            phase = match.group(1) if match else row.get('phase', 'unknown')
                            group = phases.setdefault(phase, {'views': 0, 'failed': 0})
                            group['views'] += 1
                            group['failed'] += int(float(row['metric_value']))
                runs[name]['phase_seconds'] = phases
                runs[name]['http_status_counts'] = statuses
                if name == 'cutover-home':
                    runs[name]['swap_log_utc'] = swap_iso
                    runs[name]['paired_swap'] = paired_swap
                    runs[name]['http_reads_definitely_sent_after_swap'] = after_swap
            lifecycle = size / 'cutover.json'
            if lifecycle.exists():
                state = json.loads(lifecycle.read_text())
                runs['cutover'] = {'converged': state.get('converged', True), 'elapsed_s': state['elapsed_s'],
                     'status': state['state']['status'], 'checkpoint': state['state']['checkpoint'],
                     'cursor_probes': state.get('cursor_probes', []),
                     'grouping_queries': None, 'card_queries': None}
                for query, key in [('product_groups', 'grouping_queries'), ('product_cards', 'card_queries')]:
                    before = counter(size / 'cutover-before.prom', query)
                    after = counter(size / 'cutover-after.prom', query)
                    if before is not None and after is not None: runs['cutover'][key] = after - before
            runs['query_counters_total'] = {q: counter(size / 'metrics.prom', q) for q in ['product_groups', 'product_cards']}
            result['sizes'][size.name] = runs
        output[label] = result
    print(json.dumps(output, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main(sys.argv[1], set(sys.argv[2:]) or None)
