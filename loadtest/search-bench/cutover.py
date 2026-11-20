"""Clone isolated benchmark data, or trigger the REAL FullReindexJob from CUTOVER.
Snapshot export, verification and Kafka replay precede this benchmark and are not timed.
Never targets the shared compose stack. Only standard library dependencies.
"""
import argparse
import json
import time
import re
import urllib.request
import urllib.error
import urllib.parse
from pathlib import Path

ES = 'http://elasticsearch:9200'
SEARCH = 'http://search:8080'

def call(method, url, body=None):
    req = urllib.request.Request(url, None if body is None else json.dumps(body).encode(),
                                 {'Content-Type': 'application/json'}, method=method)
    with urllib.request.urlopen(req, timeout=300) as response:
        raw = response.read()
        return json.loads(raw) if raw else None

def clone(alias):
    old = next(iter(call('GET', f'{ES}/_alias/{alias}')))
    generations = call('GET', f'{ES}/{alias}_v*?filter_path=*.aliases')
    versions = [int(name.rsplit('_v', 1)[1]) for name in generations if re.fullmatch(alias + r'_v\d+', name)]
    new = f'{alias}_v{max(versions) + 1}'
    definition = call('GET', f'{ES}/{old}')[old]
    settings = {k: v for k, v in definition['settings']['index'].items()
                if k in ('number_of_shards', 'analysis')}
    settings['number_of_replicas'] = 0
    call('PUT', f'{ES}/{new}', {'settings': settings, 'mappings': definition['mappings']})
    result = call('POST', f'{ES}/_reindex?refresh=true', {'source': {'index': old}, 'dest': {'index': new}})
    assert not result.get('failures') and not result.get('timed_out'), result
    return new

def main():
    p = argparse.ArgumentParser()
    p.add_argument('mode', choices=['reset', 'prepare', 'trigger'])
    args = p.parse_args()
    if args.mode == 'reset':
        # This URL exists only on the dedicated compose network. Remove earlier size's
        # retained generations BEFORE seed.py attaches fresh v1 aliases.
        indices = call('GET', f'{ES}/_cat/indices?format=json')
        for index in indices:
            name = index['index']
            if re.fullmatch(r'(products|categories)_v\d+', name):
                call('DELETE', f'{ES}/{name}')
        return
    if args.mode == 'prepare':
        targets = {name: clone(name) for name in ['products', 'categories']}
        Path('/out/targets.json').write_text(json.dumps(targets))
        return
    time.sleep(10)
    begin = time.time()
    Path('/out/cutover-before.prom').write_bytes(urllib.request.urlopen(f'{SEARCH}/actuator/prometheus').read())
    cursor_query = 'sort=price_asc&size=1&in_stock=false'
    cursor = call('GET', f'{SEARCH}/api/v2/search/products?{cursor_query}')['next_cursor']
    cursor_probes = []
    def probe_cursor():
        nonlocal cursor
        if cursor is None:
            return
        try:
            page = call('GET', f'{SEARCH}/api/v2/search/products?{cursor_query}&cursor={urllib.parse.quote(cursor)}')
            cursor_probes.append({'elapsed_s': time.time() - begin, 'status': 200, 'total': page['total_products']})
            cursor = page['next_cursor']
        except urllib.error.HTTPError as error:
            cursor_probes.append({'elapsed_s': time.time() - begin, 'status': error.code,
                                  'problem': json.loads(error.read())})
            cursor = None
    next_cursor_probe = time.time() + 10
    run = call('POST', f'{SEARCH}/benchmark/cutover', json.loads(Path('/out/targets.json').read_text()))
    samples = []
    converged = False
    while time.time() - begin < 140:
        if time.time() >= next_cursor_probe:
            probe_cursor()
            next_cursor_probe = time.time() + 10
        state = call('GET', f'{SEARCH}/benchmark/runs/{run["run_id"]}')
        aliases = {a: list(call('GET', f'{ES}/_alias/{a}')) for a in ['products', 'product_cards']}
        samples.append({'elapsed_s': time.time() - begin, 'status': state['status'], 'aliases': aliases})
        if state['status'] in ['COMPLETED', 'FAILED', 'SUPERSEDED']:
            # Continue observing the old implementation's post-cutover projector rebuild.
            if state['status'] != 'COMPLETED':
                raise RuntimeError(state)
            card = aliases['product_cards'][0]
            meta = call('GET', f'{ES}/{card}/_mapping')[card]['mappings'].get('_meta', {})
            if meta.get('source_index') == json.loads(Path('/out/targets.json').read_text())['products']:
                converged = True
                break
        time.sleep(.5)
    probe_cursor()
    Path('/out/cutover.json').write_text(json.dumps({'elapsed_s': time.time() - begin,
         'converged': converged, 'state': state, 'samples': samples, 'cursor_probes': cursor_probes}, indent=2))
    Path('/out/cutover-after.prom').write_bytes(urllib.request.urlopen(f'{SEARCH}/actuator/prometheus').read())

if __name__ == '__main__':
    main()
