"""Read-only pipeline verification inside ecommerce-network, after observability-audit.py.

python verify-observability.py --evidence /repo/docs/observability/fix-evidence-2026-10-18
"""
import argparse
import base64
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def fetch(url, auth=None):
    headers = {}
    if auth:
        headers['Authorization'] = 'Basic ' + base64.b64encode(auth.encode()).decode()
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=10) as response:
            data = response.read().decode()
            return response.status, json.loads(data) if data.startswith(('{', '[')) else data
    except (OSError, urllib.error.URLError, ValueError) as error:
        return None, str(error)


def query(expression):
    status, body = fetch('http://prometheus:9090/api/v1/query?' + urllib.parse.urlencode({'query': expression}))
    return body.get('data', {}).get('result', []) if status == 200 and isinstance(body, dict) else []


def eventually(probe, seconds=60):
    deadline = time.monotonic() + seconds
    while True:
        value = probe()
        if value or time.monotonic() >= deadline:
            return value
        time.sleep(3)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', required=True)
    parser.add_argument('--catalog-cache-ttl', type=int, default=10,
                        help='PDP catalog cache TTL in seconds; wait for expiry before asserting Product is called')
    parser.add_argument('--grafana-user', default=os.getenv('GRAFANA_ADMIN_USER', 'admin'))
    parser.add_argument('--grafana-password', default=os.getenv('GRAFANA_ADMIN_PASSWORD', 'local-observability'))
    args = parser.parse_args()
    output = Path(args.evidence)
    output.mkdir(parents=True, exist_ok=True)
    runtime = json.loads((output / 'runtime-probes.json').read_text())
    checks = []

    def check(name, ok, details):
        checks.append({'name': name, 'pass': bool(ok), 'details': details})
        print(('PASS ' if ok else 'FAIL ') + name, flush=True)

    expected = {service['service'] for service in runtime['services']} | {'api-gateway'}
    def targets_ready():
        status, body = fetch('http://prometheus:9090/api/v1/targets')
        if status != 200 or not isinstance(body, dict):
            return None
        targets = body['data']['activeTargets']
        up = {t['labels'].get('service') for t in targets if t['health'] == 'up'}
        return targets if expected <= up else None
    targets = eventually(targets_ready, 75)
    if not targets:
        _, body = fetch('http://prometheus:9090/api/v1/targets')
        targets = body.get('data', {}).get('activeTargets', []) if isinstance(body, dict) else []
    simplified = [{'service': t['labels'].get('service'), 'health': t['health'], 'error': t['lastError']} for t in targets]
    check('Prometheus scrapes every enabled backend and gateway', expected <= {t['service'] for t in simplified if t['health'] == 'up'}, simplified)
    check('All infrastructure/exporter targets are healthy', bool(targets) and all(t['health'] == 'up' for t in targets), simplified)
    dependency_health = {
        'redis': query('redis_up'),
        'postgres': query('pg_up'),
        'kafka': query('kafka_brokers'),
    }
    redis_ready = {v['metric'].get('service') for v in dependency_health['redis'] if float(v['value'][1]) == 1}
    check('Exporters connect to Redis, PostgreSQL and Kafka',
          {'redis', 'checkout-redis'} <= redis_ready
          and bool(dependency_health['postgres']) and all(float(v['value'][1]) == 1 for v in dependency_health['postgres'])
          and bool(dependency_health['kafka']) and all(float(v['value'][1]) >= 1 for v in dependency_health['kafka']),
          dependency_health)

    status, rules = fetch('http://prometheus:9090/api/v1/rules')
    groups = rules.get('data', {}).get('groups', []) if isinstance(rules, dict) else []
    all_rules = [r for group in groups for r in group['rules']]
    check('Recording and alert rules evaluate without errors', status == 200 and len(all_rules) >= 9 and all(r['health'] == 'ok' for r in all_rules),
          [{'name': r['name'], 'health': r['health'], 'error': r.get('lastError')} for r in all_rules])

    histogram_services = {v['metric']['service'] for v in query('count by(service) (http_server_requests_seconds_bucket or http_server_request_duration_seconds_bucket)')}
    check('Every backend provides HTTP latency buckets', expected - {'api-gateway'} <= histogram_services, sorted(histogram_services))
    check('Traefik metrics contain request counters', bool(query('traefik_service_requests_total')), 'traefik_service_requests_total')

    def backend_traces():
        status, body = fetch('http://jaeger:16686/api/traces/' + runtime['trace_id'])
        if status == 200 and isinstance(body, dict) and body.get('data'):
            services = {p['serviceName'] for p in body['data'][0]['processes'].values()}
            return sorted(services) if expected <= services else None
        return None
    exported = eventually(backend_traces, 60)
    check('Every backend and gateway exports a span via the Collector', exported, exported or [])

    def find_logs():
        params = {'query': '{project="e-commerce-microservices",service_name=~".+"} |= "' + runtime['run_id'] + '"',
                  'start': str(time.time_ns() - 900 * 10**9), 'end': str(time.time_ns()), 'limit': '5000'}
        status, body = fetch('http://loki:3100/loki/api/v1/query_range?' + urllib.parse.urlencode(params))
        if status == 200 and isinstance(body, dict):
            streams = body.get('data', {}).get('result', [])
            found = {s['stream'].get('service_name') for s in streams}
            return streams if expected <= found else None
        return None
    logs = eventually(find_logs, 60)
    if logs:
        check('Loki receives correlated access logs from every backend and gateway', True,
              {s['stream']['service_name']: len(s['values']) for s in logs})
    else:
        check('Loki receives correlated access logs from every backend and gateway', False, 'Missing streams or run ID; inspect collector and JSON access logs')

    trace_id = uuid.uuid4().hex
    request_id = 'obs-pipeline-' + uuid.uuid4().hex[:16]
    headers = {'traceparent': f'00-{trace_id}-00f067aa0ba902b7-01', 'X-Request-Id': request_id}
    # A warm in-process catalog cache correctly skips Product. Probe a cold read for the full topology.
    time.sleep(args.catalog_cache_ttl + 1)
    request = urllib.request.Request('http://api-gateway:80/api/v1/storefront/products/camera-luma-home', headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            read_status = response.status
    except urllib.error.HTTPError as error:
        read_status = error.code
    except (OSError, urllib.error.URLError):
        read_status = None
    expected_trace = {'api-gateway', 'product-detail-bff', 'product-service', 'inventory-service', 'review-service'}
    def find_trace():
        status, body = fetch('http://jaeger:16686/api/traces/' + trace_id)
        if status != 200 or not isinstance(body, dict) or not body.get('data'):
            return None
        trace_data = body['data'][0]
        services = {p['serviceName'] for p in trace_data['processes'].values()}
        return trace_data if expected_trace <= services else None
    trace_data = eventually(find_trace, 60)
    if not trace_data:
        status, body = fetch('http://jaeger:16686/api/traces/' + trace_id)
        if status == 200 and isinstance(body, dict) and body.get('data'):
            trace_data = body['data'][0]
    trace_services = sorted({p['serviceName'] for p in trace_data['processes'].values()}) if trace_data else []
    check('Gateway -> PDP -> Product/Inventory/Review shares an exported trace', read_status == 200 and expected_trace <= set(trace_services),
          {'trace_id': trace_id, 'request_id': request_id, 'http_status': read_status, 'services': trace_services})
    if trace_data:
        spans = trace_data['spans']
        ids = {span['spanID'] for span in spans}
        processes = trace_data['processes']
        children = [s for s in spans if processes[s['processID']]['serviceName'] != 'api-gateway']
        check('Downstream spans have parents in the same trace', children and all(any(ref['spanID'] in ids for ref in s.get('references', [])) for s in children),
              {'span_count': len(spans)})
        by_id = {span['spanID']: span for span in spans}
        def kind(span):
            return next((tag['value'] for tag in span.get('tags', []) if tag['key'] == 'span.kind'), None)
        def service(span):
            return processes[span['processID']]['serviceName']
        topology = {}
        for name in expected_trace - {'api-gateway', 'product-detail-bff'}:
            servers = [s for s in spans if service(s) == name and kind(s) == 'server']
            linked = False
            for server in servers:
                for ref in server.get('references', []):
                    parent = by_id.get(ref['spanID'])
                    if parent and service(parent) == 'product-detail-bff' and kind(parent) == 'client':
                        linked = any(service(by_id[r['spanID']]) == 'product-detail-bff'
                                     and kind(by_id[r['spanID']]) == 'server'
                                     for r in parent.get('references', []) if r['spanID'] in by_id)
                    if linked:
                        break
            topology[name] = linked
        check('Each PDP dependency has a client/server pair parented by the PDP request', all(topology.values()), topology)
        (output / 'pdp-trace.json').write_text(json.dumps(trace_data, indent=2) + '\n')

    auth = args.grafana_user + ':' + args.grafana_password
    status, sources = fetch('http://grafana:3000/api/datasources', auth)
    uids = sorted(s['uid'] for s in sources) if status == 200 and isinstance(sources, list) else []
    check('Grafana provisions metrics/logs/traces/alerts datasources', {'prometheus', 'loki', 'jaeger', 'alertmanager'} <= set(uids), uids)
    loki_source = next((s for s in sources if s.get('uid') == 'loki'), {}) if isinstance(sources, list) else {}
    derived = loki_source.get('jsonData', {}).get('derivedFields', [])
    check('Grafana log-to-trace link preserves the trace ID template',
          any(d.get('datasourceUid') == 'jaeger' and d.get('url') == '${__value.raw}' for d in derived), derived)
    status, dashboard = fetch('http://grafana:3000/api/dashboards/uid/backend-observability', auth)
    check('Backend dashboard is provisioned', status == 200 and isinstance(dashboard, dict) and len(dashboard.get('dashboard', {}).get('panels', [])) >= 10, status)
    (output / 'pipeline-checks.json').write_text(json.dumps(checks, indent=2) + '\n')
    return 0 if all(c['pass'] for c in checks) else 1


if __name__ == '__main__':
    raise SystemExit(main())
