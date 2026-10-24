"""Exercise scrape -> rule pending/firing -> Alertmanager using a disposable file-SD target.

Mount the prometheus-targets volume at /targets and run in ecommerce-network.
No backend is stopped. The original discovery file is restored even if verification fails.
"""
import argparse
import json
from pathlib import Path
import time
import urllib.request
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', required=True)
args = parser.parse_args()
target_file = Path('/targets/optional.json')
original = target_file.read_text()
targets = json.loads(original)
probe_service = 'obs-fault-probe-' + uuid.uuid4().hex[:8]
targets.append({'targets': ['127.0.0.1:1'], 'labels': {'service': probe_service}})
result = {'pass': False, 'service': probe_service}
try:
    target_file.write_text(json.dumps(targets))
    deadline = time.monotonic() + 210
    print('Waiting for the disposable target-down alert (2 minute for-period).', flush=True)
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen('http://alertmanager:9093/api/v2/alerts', timeout=5) as response:
                alerts = json.load(response)
            matched = [a for a in alerts if a['labels'].get('alertname') == 'TelemetryTargetDown'
                       and a['labels'].get('service') == probe_service]
            if matched:
                result = {'pass': True, 'service': probe_service, 'alerts': matched}
                print('PASS: Alertmanager received the evaluated target-down alert.', flush=True)
                break
        except OSError:
            pass
        time.sleep(5)
finally:
    target_file.write_text(original)
    Path(args.output).write_text(json.dumps(result, indent=2) + '\n')
    print('Restored the original discovery file.', flush=True)
if not result['pass']:
    raise SystemExit('FAIL: evaluated alert did not reach Alertmanager')
