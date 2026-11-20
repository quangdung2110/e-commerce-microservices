"""Configure optional profiles explicitly; a stopped enabled service remains a scrape failure."""
import json
import os
from pathlib import Path

spec = {
    "payment": ("payment-service:8080", "/actuator/prometheus", "payment-service"),
    "notification": ("notification-service:8081", "/metrics", "notification-service"),
}
enabled = os.getenv("OBSERVABILITY_OPTIONAL_SERVICES", "").replace(",", " ").split()
unknown = set(enabled) - spec.keys()
if unknown:
    raise ValueError(f"Unknown optional service profiles: {sorted(unknown)}")
targets = [
    {"targets": [spec[name][0]], "labels": {"__metrics_path__": spec[name][1], "service": spec[name][2]}}
    for name in enabled
]
Path("/targets/optional.json").write_text(json.dumps(targets), encoding="utf-8")
